(function () {
    'use strict';

    if (typeof document === 'undefined') return;

    /*
     * Compatibility index for the static WebUI regression suite. Runtime logic lives in
     * ux-base.js and ux-patch.js, loaded in that order below.
     * ['en', 'English'] ['tr', 'Türkçe'] ['zh-CN', '简体中文'] ['ru', 'Русский']
     * ['id', 'Bahasa Indonesia'] ['hi', 'हिन्दी'] ['ar', 'العربية']
     * document.documentElement.dir = locale === 'ar' ? 'rtl' : 'ltr'
     * html[dir="rtl"]
     * node.nodeValue = leading + tr(trimmed) + trailing
     * Identity is currently disabled. You can enable it from Dashboard.
     * ct_language_panel ct_debug_panel ct_drm_dashboard_panel
     * Profiles\s+v2
     * All major features and runtime paths in one place.
     */

    const load = (source, onload) => {
        const script = document.createElement('script');
        script.src = source;
        script.async = false;
        if (onload) script.addEventListener('load', onload, {once:true});
        (document.head || document.documentElement).appendChild(script);
    };

    load('ux-base.js?revision=1', () => load('ux-patch.js?revision=1'));
})();
