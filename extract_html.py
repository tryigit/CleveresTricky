import re

with open('service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt', 'r') as f:
    content = f.read()

# Extract content between triple quotes in getHtml()
# The kotlin code is: private fun getHtml(): String {\n        return """\n...
match = re.search(r'private fun getHtml\(\): String \{\s+return """(.*?)""".trimIndent\(\)', content, re.DOTALL)

if match:
    html = match.group(1)

    # Handle Kotlin string templates
    # ${getAppName()} -> CleveresTricky
    html = html.replace('${getAppName()}', 'CleveresTricky')

    # The file uses ${'$'} to escape $ in JS template literals
    # We want to convert ${'$'} back to $
    html = html.replace("${'$'}", "$")

    with open('index.html', 'w') as f:
        f.write(html)
    print("HTML extracted to index.html")
else:
    print("Could not find HTML content")
