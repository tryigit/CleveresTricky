(function () {
    'use strict';

    if (typeof document === 'undefined') return;

    // policy.js owns feature controls, Profiles, Security Patch and Effective State.
    // ux-base.js owns localization and general presentation only. Keep a single loader
    // here so older bridge builds can continue requesting ux.js without reintroducing
    // the retired overlay layer.
    const script = document.createElement('script');
    script.src = 'ux-base.js?revision=5';
    script.async = false;
    (document.head || document.documentElement).appendChild(script);
})();
