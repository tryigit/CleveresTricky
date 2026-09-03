'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const VIOLATION_MESSAGE = 'Module change detected! Module is being deleted and system is being restarted.';

// Verify the violation message is defined in IntegrityViolationHandler.kt
const handlerSource = fs.readFileSync(
    path.resolve(__dirname, '..', '..', 'service', 'src', 'main', 'java',
        'cleveres', 'tricky', 'cleverestech', 'IntegrityViolationHandler.kt'),
    'utf8'
);

assert.ok(
    handlerSource.includes(VIOLATION_MESSAGE),
    'IntegrityViolationHandler.kt must contain the exact violation message'
);

// Verify the violation message is used in WebServer.kt
const webServerSource = fs.readFileSync(
    path.resolve(__dirname, '..', '..', 'service', 'src', 'main', 'java',
        'cleveres', 'tricky', 'cleverestech', 'WebServer.kt'),
    'utf8'
);

assert.ok(
    webServerSource.includes('IntegrityViolationHandler.VIOLATION_MESSAGE'),
    'WebServer.kt must reference IntegrityViolationHandler.VIOLATION_MESSAGE for the violation page'
);

assert.ok(
    webServerSource.includes('IntegrityViolationHandler.isViolated'),
    'WebServer.kt must check IntegrityViolationHandler.isViolated'
);

// Verify the violation check happens BEFORE the isTampered check
const violatedIndex = webServerSource.indexOf('IntegrityViolationHandler.isViolated');
const tamperedIndex = webServerSource.indexOf('isTampered && (trustedBridge');
assert.ok(
    violatedIndex > 0 && tamperedIndex > 0 && violatedIndex < tamperedIndex,
    'Integrity violation check must occur before the isTampered check in WebServer.kt'
);

// Verify IntegrityViolationHandler has idempotent AtomicBoolean guard
assert.ok(
    handlerSource.includes('AtomicBoolean'),
    'IntegrityViolationHandler must use AtomicBoolean for idempotent violation handling'
);
assert.ok(
    handlerSource.includes('compareAndSet(false, true)'),
    'IntegrityViolationHandler must use compareAndSet for idempotent guard'
);

// Verify the violation handler has injectable test hooks
assert.ok(
    handlerSource.includes('internal var deleteModule'),
    'IntegrityViolationHandler must have injectable deleteModule for testing'
);
assert.ok(
    handlerSource.includes('internal var rebootSystem'),
    'IntegrityViolationHandler must have injectable rebootSystem for testing'
);
assert.ok(
    handlerSource.includes('resetForTesting'),
    'IntegrityViolationHandler must have resetForTesting'
);

// Verify Main.kt integrates integrity verification before native loading
const mainSource = fs.readFileSync(
    path.resolve(__dirname, '..', '..', 'service', 'src', 'main', 'java',
        'cleveres', 'tricky', 'cleverestech', 'Main.kt'),
    'utf8'
);

const integrityVerifyIndex = mainSource.indexOf('ModuleIntegrityVerifier.verifyFull');
const backendAwaitIndex = mainSource.indexOf('NativeBackend.awaitReady');
assert.ok(
    integrityVerifyIndex > 0 && backendAwaitIndex > 0 && integrityVerifyIndex < backendAwaitIndex,
    'Integrity verification must happen BEFORE NativeBackend.awaitReady in Main.kt'
);

console.log('integrity-violation.test.js: all assertions passed');
