export default async function (b, check) {
  const q = (s) => `document.querySelector(${JSON.stringify(s)})`;
  check('server site runs in a worker', await b.waitFor(`${q('.where')}?.textContent === 'server site runs in a worker'`));
  await b.evaluate(`${q('#add')}.click()`);
  await b.evaluate(`${q('#add')}.click()`);
  check('worker state updates the page', await b.waitFor(`document.querySelectorAll('li').length === 2`));
  check('values', (await b.evaluate(`[...document.querySelectorAll('li')].map(l => l.textContent).join('|')`)) === 'note 1|note 2');
}
