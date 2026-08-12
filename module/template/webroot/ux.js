(function () {
    'use strict';

    if (typeof document === 'undefined') return;

    const load = (source, onload) => {
        const script = document.createElement('script');
        script.src = source;
        script.async = false;
        if (onload) script.addEventListener('load', onload, {once:true});
        (document.head || document.documentElement).appendChild(script);
    };

    load('ux-base.js?revision=1', () => load('ux-patch.js?revision=1'));
})();
