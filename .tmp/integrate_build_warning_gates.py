from pathlib import Path

path = Path('.github/workflows/build.yml')
text = path.read_text()

replacements = {
    '        run: ./gradlew ktlintCheck\n': '        run: ./gradlew ktlintCheck --warning-mode=fail --console=plain\n',
    '        run: ./gradlew :service:lintDebug :stub:lintDebug :encryptor-app:lintDebug --continue\n': '        run: ./gradlew :service:lintDebug :stub:lintDebug :encryptor-app:lintDebug --warning-mode=fail --console=plain --continue\n',
    '        run: ./gradlew testDebugUnitTest --console=plain --stacktrace\n': '        run: ./gradlew testDebugUnitTest --warning-mode=fail --console=plain --stacktrace --no-build-cache --rerun-tasks\n',
    '          ./gradlew zipRelease\n          ./gradlew zipDebug\n          ./gradlew :encryptor-app:assembleRelease\n': '          ./gradlew zipRelease --warning-mode=fail --console=plain\n          ./gradlew zipDebug --warning-mode=fail --console=plain\n          ./gradlew :encryptor-app:assembleRelease --warning-mode=fail --console=plain\n',
}

for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f'expected one match for {old!r}, found {text.count(old)}')
    text = text.replace(old, new)

old_webui = '''          node --check module/template/webroot/bridge.js
          node <<'NODE'
          const fs = require('fs')
          const html = fs.readFileSync('module/template/webroot/index.html', 'utf8')
          const blocks = [...html.matchAll(/<script([^>]*)>([\\s\\S]*?)<\\/script>/gi)]
            .filter(match => !/\\bsrc\\s*=/.test(match[1]))
          if (blocks.length !== 1) throw new Error('Unexpected inline script count')
          new Function(blocks[0][2])
          NODE
'''
new_webui = '''          node --check module/template/webroot/bridge.js
          node --check module/template/webroot/policy.js
          node <<'NODE'
          const fs = require('fs')
          const html = fs.readFileSync('module/template/webroot/index.html', 'utf8')
          const policyScripts = [...html.matchAll(/<script[^>]*\\bsrc=["']policy\\.js(?:\\?[^"']*)?["'][^>]*>/gi)]
          if (policyScripts.length !== 1) throw new Error('Expected exactly one policy.js script entrypoint')
          const blocks = [...html.matchAll(/<script([^>]*)>([\\s\\S]*?)<\\/script>/gi)]
            .filter(match => !/\\bsrc\\s*=/.test(match[1]))
          if (blocks.length !== 1) throw new Error('Unexpected inline script count')
          new Function(blocks[0][2])
          NODE
'''
if text.count(old_webui) != 1:
    raise SystemExit(f'expected one WebUI block, found {text.count(old_webui)}')
text = text.replace(old_webui, new_webui)
path.write_text(text)
