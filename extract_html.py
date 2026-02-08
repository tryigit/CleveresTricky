import re

with open("service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt", "r") as f:
    content = f.read()

match = re.search(r'private val htmlContent by lazy \{\s*"""(.*?)"""\.trimIndent\(\)', content, re.DOTALL)
if match:
    html = match.group(1)
    # The variable ${getAppName()} needs to be replaced or it will show literally.
    # In Kotlin """ string, ${getAppName()} is interpolated.
    # I should replace it with "CleveresTricky" to emulate the server.
    html = html.replace("${getAppName()}", "CleveresTricky")
    # Also replace ${'$'} with $ because Kotlin source escapes $ as ${'$'}.
    html = html.replace("${'$'}", "$")

    with open("index.html", "w") as out:
        out.write(html)
    print("Extracted HTML")
else:
    print("Could not find HTML")
