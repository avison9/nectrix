import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import boundaries from "eslint-plugin-boundaries";

// Each app's package.json lint script runs `eslint .` from inside that app's
// own directory, so file paths reaching the boundaries plugin are relative
// to the app, not the repo root. Without an explicit root-path, "apps/web/**"
// never matches anything and the rule silently never fires. import.meta.dirname
// always resolves to this file's own directory (the repo root), regardless of
// which app's eslint.config.mjs re-exports it or what cwd eslint runs from.
const repoRoot = import.meta.dirname;

// Single source of truth for workspace-wide lint rules, including the
// module-boundary rule below. Each app's own eslint.config.mjs re-exports
// this file so `eslint .` run from an app directory picks it up without
// relying on ESLint's own (nonexistent, in flat-config mode) directory walk.
const rootConfig = [
  { ignores: ["**/.next/**", "**/dist/**", "**/node_modules/**", "**/build/**"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: { boundaries },
    settings: {
      "boundaries/root-path": repoRoot,
      "boundaries/elements": [
        { type: "app-web", pattern: "apps/web/**" },
        { type: "app-admin", pattern: "apps/admin-portal/**" },
        { type: "package", pattern: "packages/*/**" },
      ],
    },
    rules: {
      "boundaries/dependencies": [
        "error",
        {
          default: "disallow",
          policies: [
            {
              from: { element: { types: "app-web" } },
              allow: { to: { element: { types: { anyOf: ["app-web", "package"] } } } },
            },
            {
              from: { element: { types: "app-admin" } },
              allow: { to: { element: { types: { anyOf: ["app-admin", "package"] } } } },
            },
            {
              from: { element: { types: "package" } },
              allow: { to: { element: { types: "package" } } },
            },
          ],
        },
      ],
    },
  },
];

export default rootConfig;
