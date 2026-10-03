export default async function (b, check) {
  const q = (s) => `document.querySelector(${JSON.stringify(s)})`;
  const all = (s) => `[...document.querySelectorAll(${JSON.stringify(s)})]`;
  check('rows rendered', await b.waitFor(`${all('.row')}.length > 0`));
  // rows wrap to different heights; the window renders only what fits
  check('window renders few rows', (await b.evaluate(`${all('.row')}.length`)) < 30);
  const heights = await b.evaluate(`[...new Set(${all('.row')}.map(r => Math.round(r.getBoundingClientRect().height)))].length`);
  check('rows have different heights', heights > 1, `${heights} distinct heights`);
  await b.evaluate(`${q('.curve-window')}.scrollTop = 2000; ${q('.curve-window')}.dispatchEvent(new Event('scroll'))`);
  check('scrolling renders later rows', await b.waitFor(`!${all('.row')}.some(r => r.textContent.startsWith('row 0'))`));
  const firstVisibleAligned = await b.evaluate(`(() => {
    const w = ${q('.curve-window')}.getBoundingClientRect();
    return ${all('.row')}.some(r => { const x = r.getBoundingClientRect(); return x.top <= w.top + 1 && x.bottom > w.top; });
  })()`);
  check('a row covers the top of the window (spacers match measured heights)', firstVisibleAligned);
  check('comments not mounted before scrolling', (await b.evaluate(`${q('.comments')} === null && ${q('.wait')} !== null`)));
  await b.evaluate(`window.scrollTo(0, document.body.scrollHeight)`);
  check('comments mount when scrolled into view', await b.waitFor(`${q('.count')}?.textContent?.startsWith('queried') === true`));
}
