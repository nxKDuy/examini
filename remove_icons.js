const fs = require('fs')
const path = require('path')

function walkDir(dir, callback) {
  fs.readdirSync(dir).forEach(f => {
    const dirPath = path.join(dir, f)
    if (fs.statSync(dirPath).isDirectory()) {
      walkDir(dirPath, callback)
    } else {
      callback(path.join(dir, f))
    }
  })
}

const icons = new Set()

walkDir('./frontend/src', filePath => {
  if (!filePath.endsWith('.tsx')) return
  
  let content = fs.readFileSync(filePath, 'utf8')
  
  // Find all imported icons
  const importMatch = content.match(/import\s+{([^}]+)}\s+from\s+['"]lucide-react['"]/)
  if (!importMatch) return

  const importedIcons = importMatch[1].split(',').map(s => s.trim()).filter(Boolean)
  
  // Remove the import line
  content = content.replace(/import\s+{[^}]+}\s+from\s+['"]lucide-react['"];?\n?/, '')

  // Remove the icon tags
  importedIcons.forEach(icon => {
    // Matches <IconName ... />, <IconName ...></IconName>
    const regex = new RegExp(`<${icon}\\b[^>]*\\/?>`, 'g')
    content = content.replace(regex, '')
  })

  fs.writeFileSync(filePath, content)
  console.log(`Removed icons from ${filePath}`)
})
