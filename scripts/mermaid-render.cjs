const fs = require('fs');
const path = require('path');

const [, , jobsFile, mermaidJs] = process.argv;
const playwright = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const font = 'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", "PingFang TC", "Noto Sans TC", "Noto Sans CJK TC", "Microsoft JhengHei", sans-serif';

const palettes = {
  light: { paper: '#f6f4ee', paper2: '#efece3', paper3: '#e5e1d6', ink: '#1b1915', muted: '#6d675d', line: '#c6c0b1', accent: '#974a1e', wash: '#ead9c8' },
  dark: { paper: '#121110', paper2: '#1a1917', paper3: '#252320', ink: '#ece7dc', muted: '#9b9488', line: '#403d37', accent: '#e0a37c', wash: '#2b211a' },
};

function config(name) {
  const c = palettes[name];
  const dark = name === 'dark';
  return {
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    darkMode: dark,
    fontFamily: font,
    flowchart: { useMaxWidth: true, curve: 'basis', padding: 14, htmlLabels: false },
    themeVariables: {
      darkMode: dark,
      background: c.paper2,
      fontFamily: font,
      fontSize: '14px',
      primaryColor: c.paper,
      primaryTextColor: c.ink,
      primaryBorderColor: c.accent,
      secondaryColor: c.wash,
      secondaryTextColor: c.ink,
      secondaryBorderColor: c.line,
      tertiaryColor: c.paper3,
      tertiaryTextColor: c.ink,
      tertiaryBorderColor: c.line,
      lineColor: c.muted,
      textColor: c.ink,
      mainBkg: c.paper,
      nodeBorder: c.accent,
      clusterBkg: c.paper3,
      clusterBorder: c.line,
      titleColor: c.ink,
      edgeLabelBackground: c.paper2,
      noteBkgColor: c.wash,
      noteTextColor: c.ink,
      noteBorderColor: c.line,
      actorBkg: c.paper,
      actorBorder: c.accent,
      actorTextColor: c.ink,
      signalColor: c.muted,
      signalTextColor: c.ink,
      labelBoxBkgColor: c.paper,
      labelBoxBorderColor: c.line,
      labelTextColor: c.ink,
      pie1: c.accent,
      pie2: c.wash,
      pie3: c.muted,
      pie4: c.line,
    },
  };
}

(async () => {
  const jobs = JSON.parse(fs.readFileSync(jobsFile, 'utf8'));
  const browser = await playwright.chromium.launch();
  const page = await browser.newPage();
  await page.setContent('<!doctype html><html><head><meta charset="utf-8"></head><body></body></html>');
  await page.addScriptTag({ path: mermaidJs });
  let ok = 0;
  let failed = 0;
  for (const job of jobs) {
    for (const name of ['light', 'dark']) {
      const id = `mm-${job.hash.slice(0, 12)}-${name[0]}`;
      const result = await page.evaluate(
        async ({ code, cfg, id }) => {
          try {
            window.mermaid.initialize(cfg);
            const { svg } = await window.mermaid.render(id, code);
            return { svg };
          } catch (e) {
            document.querySelectorAll(`#${id}, #d${id}`).forEach((n) => n.remove());
            return { error: String(e && e.message ? e.message : e).split('\n')[0] };
          }
        },
        { code: job.code, cfg: config(name), id }
      );
      if (result.error) {
        failed++;
        process.stdout.write(`FAIL\t${job.hash}\t${name}\t${result.error}\n`);
        break;
      }
      fs.mkdirSync(path.dirname(job.out), { recursive: true });
      const file = name === 'light' ? job.out : job.out.replace(/\.svg$/, '.dark.svg');
      fs.writeFileSync(file, result.svg);
      if (name === 'dark') ok++;
    }
  }
  await browser.close();
  process.stdout.write(`DONE\t${ok}\t${failed}\n`);
})();
