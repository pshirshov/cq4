import { chromium } from 'playwright';
import { driversChecks } from './drivers-browser.mjs';
const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext(); const page = await context.newPage();
  await page.goto(process.env.CQ_ORIGIN);
  await page.getByLabel('Operator token').fill(process.env.CQ_TOKEN);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByText('Connection: ALIVE', { exact: true }).waitFor();
  await driversChecks(browser, await context.storageState(), process.env.CQ_ORIGIN, process.env.CQ_BROWSER_EVIDENCE);
  await context.close();
} finally { await browser.close(); }
