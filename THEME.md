# CleveresTricky UI Theme

This document tracks the color palette and design system used in the CleveresTricky Web UI.

## Design Philosophy: "Nothing / iOS Hybrid"

The UI follows a minimalist, monochrome aesthetic inspired by **Nothing OS** and **iOS**.
*   **Nothing OS Influence:** Use of dot-matrix fonts (simulated via monospace), raw industrial look, high contrast black/white, and "glitch" aesthetic for technical data.
*   **iOS Influence:** Fluid animations, "Dynamic Island" for notifications, rounded corners (Apple-style), and blur effects.

## Color Palette

| Name | Hex Code | Description | Usage |
|------|----------|-------------|-------|
| **Background** | `#0B0B0C` | Matte Dark Charcoal | Main page background |
| **Foreground** | `#E5E7EB` | Light Gray | Primary text color |
| **Accent** | `#D1D5DB` | Silver | Highlights, active tabs, primary headers |
| **Panel** | `#161616` | Dark Gray | Cards, containers, tab bar background |
| **Border** | `#333333` | Dark Grey | Borders, dividers |
| **Input Background** | `#1A1A1A` | Very Dark Gray | Text inputs, textareas |
| **Success** | `#34D399` | Emerald Green | Success messages, valid status |
| **Danger** | `#EF4444` | Red | Errors, delete buttons, critical warnings |

## Design Components

### Typography
*   **Font Family**: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif
*   **Headers**: Uppercase, tracked (letter-spacing: 2px), lightweight (200-300).
*   **Technical Data**: Monospace font to simulate the "Nothing" dot-matrix look.

### UI Elements
*   **Dynamic Island**: Floating notification pill, black background, box-shadow.
*   **Tabs**: Panel background, bottom border, active state uses Accent color.
*   **Buttons**: Uppercase, rounded corners (6px), flat design. Primary buttons use Accent color.
*   **Toggles**: iOS-style switches.

## Mobile-First Design

Since this WebUI is designed for a KernelSU/APatch module, it is primarily accessed on the device through the root manager or a local browser.
*   **Responsive Layout:** The interface must be fully responsive and optimized for touch targets.
*   **Vertical Scrolling:** Prioritize vertical flow over horizontal complexity.
*   **Touch Targets:** Buttons and inputs should be at least 44px height.
