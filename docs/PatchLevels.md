# Patch Levels

## Purpose

Patch level control adjusts the certificate fields consumed by supported attestation responses. It provides independent rules for the operating system, vendor image, and boot image fields.

## Rule structure

The `security_patch.txt` file can define global defaults and per application sections. The `system`, `vendor`, and `boot` keys control their matching certificate fields. The `all` key applies one value to all three fields.

A calendar value can be written in compact form such as `20251001`. The `today` value uses the current calendar date. The `device_default` value preserves the genuine certificate field. The `prop` value reads the matching Android property. The `no` value removes that field from the modified response.

Per application sections override global rules for the matching package. The older package and date syntax remains available for operating system patch rules.

## Validation and reload

Input size, section count, package syntax, field name, date format, and value length are bounded. Parsing creates a complete replacement state. An invalid file does not partially update the running policy.

Dynamic property and date results use bounded caches. Changing the file replaces rules, clears dynamic results, and clears certificate caches so later requests reflect the new configuration.

## Limits

Patch rules modify supported certificate fields only. They do not install security updates, change kernel code, repair vendor firmware, or make a device current. Use values that match the environment being tested and understand that a remote verifier can consider other evidence.

[Return to the project overview](../README.md)

## Independent patch policies

System, Vendor, and Boot patch levels are resolved independently. Each component supports Device, Property, Manual, Automatic, and Omit modes. Device preserves the genuine authorization value. Property reads the matching Android property for that component. Manual accepts a strictly validated calendar date. Automatic evaluates the corresponding captured value first and then the corresponding property when no captured value is available. Omit removes only the selected component.

Automatic mode uses calendar arithmetic. The default age threshold is six months. A stale source resolves to day five of the previous calendar month. A recent captured value remains genuine. January correctly resolves through the previous December and leap year calendar rules are handled by the platform date API. The result is live reload compatible and cached by source date, current month, and threshold.

Captured means the genuine authorization value observed from Android attestation. Configured means the selected policy. Effective means the value that the runtime resolver will expose. An unrelated certificate modification preserves genuine System, Vendor, and Boot authorization tags, including their original software or TEE authorization list location. Malformed authorization layouts fail closed.

Existing security_patch.txt rules remain supported. When no patch override is active, genuine patch values remain untouched.
