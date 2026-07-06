import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import rootConfig from "../../eslint.config.mjs";

const config = [...rootConfig, ...nextCoreWebVitals];

export default config;
