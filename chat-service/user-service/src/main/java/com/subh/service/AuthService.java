package com.subh.service;

import com.subh.dto.*;
import com.subh.entity.User;
import com.subh.exception.ResourceNotFoundException;
import com.subh.exception.UserAlreadyExistsException;
import com.subh.repository.UserRepository;
import com.subh.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;

    /**
     * Register a new user, encode password, issue JWT pair.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check for duplicate email
        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException("User with email " + request.email() + " already exists");
        }

        // Build and save user
        User user = User.builder()
                .fullName(request.fullName())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();

        User savedUser = userRepository.save(user);

        // Generate tokens
        UserDetails userDetails = userDetailsService.loadUserByUsername(savedUser.getEmail());
        String accessToken = jwtUtil.generateAccessToken(userDetails, savedUser.getId().toString());
        String refreshToken = jwtUtil.generateRefreshToken(userDetails);

        return new AuthResponse(
                accessToken,
                refreshToken,
                savedUser.getId(),
                savedUser.getFullName(),
                savedUser.getEmail()
        );
    }

    /**
     * Authenticate user credentials and issue JWT pair.
     */
    public AuthResponse login(LoginRequest request) {
        // Authenticate via Spring Security
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        // Fetch user entity for response
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String accessToken = jwtUtil.generateAccessToken(userDetails, user.getId().toString());
        String refreshToken = jwtUtil.generateRefreshToken(userDetails);

        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getFullName(),
                user.getEmail()
        );
    }

    /**
     * Validate refresh token and issue a new access token.
     */
    public AuthResponse refreshToken(RefreshRequest request) {
        String email = jwtUtil.extractEmail(request.refreshToken());

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        if (!jwtUtil.isTokenValid(request.refreshToken(), userDetails)) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String newAccessToken = jwtUtil.generateAccessToken(userDetails, user.getId().toString());

        return new AuthResponse(
                newAccessToken,
                request.refreshToken(), // Return the same refresh token
                user.getId(),
                user.getFullName(),
                user.getEmail()
        );
    }
}
