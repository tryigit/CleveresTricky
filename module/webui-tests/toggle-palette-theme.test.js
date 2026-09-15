const assert = require('assert');
const fs = require('fs');

const indexHtml = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const uxJs = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const policyJs = fs.readFileSync('module/template/webroot/policy.js', 'utf8');

// 1. Toggle & switch active color and knob styling
assert.ok(
    uxJs.includes('input[type="checkbox"].toggle:checked, input[type="checkbox"].ct-switch:checked { background: var(--success, #30d158) !important;'),
    'ux.js must style checked toggle to system green',
);
assert.ok(
    uxJs.includes('input[type="checkbox"].toggle:checked::after, input[type="checkbox"].ct-switch:checked::after { transform: translateX(20px) !important; background: #ffffff !important; }'),
    'ux.js checked toggle knob must be white (#ffffff), never black (#0b0b0c)',
);
assert.ok(!uxJs.includes('#0b0b0c'), 'ux.js must not force a black knob on checked toggles');

assert.ok(
    policyJs.includes('input[type="checkbox"].ct-switch:checked{background:var(--success,#30d158)!important;border-color:var(--success,#30d158)!important}'),
    'policy.js must style checked switch to system green',
);
assert.ok(
    policyJs.includes('input[type="checkbox"].ct-switch:checked::after{transform:translateX(20px)!important;background:#ffffff!important}'),
    'policy.js checked switch knob must be white',
);

assert.ok(
    indexHtml.includes('input[type="checkbox"].toggle:checked, input[type="checkbox"].ct-switch:checked { background: var(--success); border-color: var(--success); }'),
    'index.html must style checked toggle to var(--success)',
);

// 2. Debug logging toggle element classes
assert.ok(
    uxJs.includes('id="ct_debug_logging_toggle" class="ct-switch toggle"'),
    'ct_debug_logging_toggle must have ct-switch toggle classes',
);

// 3. Elimination of neon glare shadows
assert.ok(
    !indexHtml.includes('rgba(10, 132, 255, 0.35)'),
    'index.html must not have neon blue box-shadow on primary button',
);
assert.ok(
    !indexHtml.includes('rgba(10, 132, 255, 0.45)'),
    'index.html must not have neon blue box-shadow on hover',
);
assert.ok(
    !policyJs.includes('rgba(251,191,36,.5)'),
    'policy.js must not have glowing neon box-shadow on pending-reboot toggles',
);

// 4. Color palette alignment
assert.ok(indexHtml.includes('--color-red: rgb(255, 69, 58);'), 'dark red must match system red');
assert.ok(indexHtml.includes('--color-orange: rgb(255, 159, 10);'), 'dark orange must match system orange');
assert.ok(indexHtml.includes('--color-yellow: rgb(255, 214, 10);'), 'dark yellow must match system yellow');
assert.ok(indexHtml.includes('--color-green: rgb(48, 209, 88);'), 'dark green must match system green');
assert.ok(indexHtml.includes('--color-blue: rgb(10, 132, 255);'), 'dark blue must match system blue');
assert.ok(indexHtml.includes('--color-indigo: rgb(94, 92, 230);'), 'dark indigo must match system indigo');
assert.ok(indexHtml.includes('--color-brown: rgb(172, 142, 104);'), 'dark brown must match system brown');

console.log('Toggle states, debug logging switch, and theme palette regression checks passed');
