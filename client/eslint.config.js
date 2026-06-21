import js from "@eslint/js";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import react from "eslint-plugin-react";
import importX from "eslint-plugin-import-x";
import globals from "globals";

export default tseslint.config(
  // Global ignores
  { ignores: ["dist"] },

  // Base config for all TS/TSX source files
  {
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommended,
      importX.flatConfigs.recommended,
      importX.flatConfigs.typescript,
    ],
    files: ["src/**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2020,
      globals: {
        ...globals.browser,
      },
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
      react,
      "import-x": importX,
    },
    settings: {
      "import-x/resolver": {
        typescript: true,
        node: true,
      },
    },
    rules: {
      // -------------------------------------------
      // React Hooks (recommended)
      // -------------------------------------------
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": [
        "warn",
        { allowConstantExport: true },
      ],

      // ===========================================
      // 1. Bug prevention
      // ===========================================
      "no-undef": "error",
      "no-unused-vars": "off", // superseded by @typescript-eslint/no-unused-vars
      eqeqeq: ["error", "always", { null: "ignore" }],
      "no-fallthrough": "error",
      "no-constant-condition": ["error", { checkLoops: false }],
      "no-self-compare": "error",

      // ===========================================
      // 2. Logic safety & correctness
      // ===========================================
      "no-unsafe-optional-chaining": "error",
      "no-extra-boolean-cast": "error",
      "no-duplicate-imports": "off", // superseded by import-x/no-duplicates
      "import-x/no-duplicates": "error",
      "no-unreachable": "error",
      "no-lonely-if": "warn",
      "no-useless-return": "warn",

      // ===========================================
      // 3. Code clarity / readability
      // ===========================================
      curly: ["error", "all"],
      "no-nested-ternary": "error",
      "prefer-const": "error",
      "no-var": "error",
      "consistent-return": "error",
      "default-case": "error",
      "default-case-last": "warn",

      // ===========================================
      // 4. Maintainability / scaling
      // ===========================================
      complexity: ["warn", 20],
      "max-depth": ["warn", 4],
      "max-lines-per-function": [
        "warn",
        { max: 150, skipBlankLines: true, skipComments: true },
      ],
      "max-params": ["warn", 4],
      "no-magic-numbers": [
        "warn",
        {
          ignore: [-1, 0, 1, 100],
          ignoreArrayIndexes: true,
          ignoreDefaultValues: true,
          ignoreClassFieldInitialValues: true,
        },
      ],

      // ===========================================
      // 5. Consistency rules (team hygiene)
      // ===========================================
      "object-shorthand": ["warn", "always"],
      "prefer-arrow-callback": "warn",
      "dot-notation": "warn",
      "padding-line-between-statements": [
        "warn",
        { blankLine: "always", prev: "*", next: "return" },
      ],

      // ===========================================
      // 6. Imports & structure
      // ===========================================
      "import-x/order": [
        "warn",
        {
          groups: [
            "builtin",
            "external",
            "internal",
            "parent",
            "sibling",
            "index",
          ],
          "newlines-between": "always",
          alphabetize: { order: "asc", caseInsensitive: true },
        },
      ],
      // React's ESM/CJS interop makes default-export checks unreliable;
      // TypeScript handles import validation instead.
      "import-x/default": "off",
      "import-x/no-unresolved": "error",
      "import-x/no-cycle": "warn",

      // ===========================================
      // 7. TypeScript-specific
      // ===========================================
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
        },
      ],
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { prefer: "type-imports", fixStyle: "separate-type-imports" },
      ],
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/no-non-null-assertion": "off",
      "@typescript-eslint/ban-ts-comment": [
        "warn",
        { "ts-expect-error": "allow-with-description" },
      ],
      "@typescript-eslint/return-await": ["error", "in-try-catch"],

      // ===========================================
      // 8. React-specific
      // ===========================================
      "react/jsx-key": ["error", { checkFragmentShorthand: true }],
      "react/no-unescaped-entities": "warn",
      "react/prop-types": "off", // TypeScript handles prop validation

      // ===========================================
      // 9. Noise control
      // ===========================================
      "no-console": "warn",
      "no-debugger": "error",
    },
  },

  // Test files: add vitest globals, relax some strictness
  {
    files: ["**/*.test.{ts,tsx}", "**/test/**/*.{ts,tsx}"],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.vitest,
      },
    },
    rules: {
      "no-magic-numbers": "off",
      "max-lines-per-function": "off",
    },
  },

  // Config files at project root (not covered by tsconfig include)
  {
    files: ["vite.config.ts"],
    languageOptions: {
      parserOptions: {
        projectService: false,
      },
    },
    rules: {},
  },
);
