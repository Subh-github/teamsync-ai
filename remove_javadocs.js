const fs = require('fs');
const path = require('path');

function walk(dir) {
    let results = [];
    const list = fs.readdirSync(dir);
    list.forEach(file => {
        file = path.join(dir, file);
        const stat = fs.statSync(file);
        if (stat && stat.isDirectory()) {
            results = results.concat(walk(file));
        } else {
            if (file.endsWith('.java')) {
                results.push(file);
            }
        }
    });
    return results;
}

const files = walk(path.join(__dirname, 'src'));

for (const file of files) {
    if (file.includes('\\service\\') || file.includes('/service/') || 
        file.includes('\\controller\\') || file.includes('/controller/')) {
        continue;
    }
    
    let content = fs.readFileSync(file, 'utf8');
    // Match Javadocs and optional surrounding whitespace, leaving a clean newline
    const regex = /[ \t]*\/\*\*[\s\S]*?\*\/\r?\n?/g;
    const newContent = content.replace(regex, '');
    
    if (newContent !== content) {
        fs.writeFileSync(file, newContent, 'utf8');
        console.log(`Updated ${file}`);
    }
}
