# Language Support

The CleveresTricky module is developed and maintained in **English by default**.

## Contributing Translations

We highly encourage the community to contribute translations to make the module accessible to a wider audience.

### How to Add a Language

You can easily translate the WebUI by creating a language file in your configuration directory.
The module will automatically load this file if it exists.

1.  Create a file named `lang.json` in `/data/adb/cleverestricky/`.
2.  Use the JSON format below.
3.  Reload the WebUI (or click "Reload Language" in the Info tab).

### Template (lang.json)

```json
{
  "tab_dashboard": "Dashboard",
  "tab_spoof": "Identity",
  "tab_apps": "Apps",
  "tab_keys": "Keyboxes",
  "tab_info": "Info & Resources",
  "tab_guide": "📖 Guide",
  "tab_editor": "Editor",

  "h1_title": "CleveresTricky",
  "section_system_control": "System Control",
  "lbl_global_mode": "Global Mode",
  "lbl_tee_broken": "Certificate Safe Mode",

  "resource_monitor_title": "Resource Monitor",
  "col_feature": "Feature",
  "col_status": "Status",
  "col_runtime": "Runtime path",
  "col_scope": "Scope",
  "col_desc": "Description"
}
```

### Community Guidelines

-   **Accuracy:** Ensure translations are accurate and reflect the technical nature of the module.
-   **Security Warnings:** Pay special attention to security warnings. Do not soften the language; users must understand the risks of disabling features.
-   **Updates:** When new features are added, please update your `lang.json` file. The module will fall back to English for any missing keys.

To submit your translation for official inclusion (for example, as a preset), please open a Pull Request.
