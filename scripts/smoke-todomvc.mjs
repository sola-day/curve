export default async function (b, check) {
  const q = (s) => `document.querySelector(${JSON.stringify(s)})`;
  const all = (s) => `[...document.querySelectorAll(${JSON.stringify(s)})]`;
  check('app rendered', await b.waitFor(`${q('.new-todo')} !== null`));
  for (const t of ['buy milk', 'write docs']) {
    await b.evaluate(`(() => { const i = ${q('.new-todo')}; i.value = ${JSON.stringify(t)};
      i.dispatchEvent(new Event('input', {bubbles: true}));
      i.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true})); })()`);
    await b.waitFor(`${all('.todo-list label')}.some(l => l.textContent === ${JSON.stringify(t)})`);
  }
  check('two todos', (await b.evaluate(`${all('.todo-list label')}.map(l => l.textContent).join('|')`)) === 'buy milk|write docs');
  await b.evaluate(`${q('.toggle')}.click()`);
  check('toggle completes', await b.waitFor(`${q('.todo-count')}.textContent === '1 item left'`));
  await b.evaluate(`${all('.filters a')}[2].click()`);
  check('completed filter', await b.waitFor(`${all('.todo-list label')}.map(l => l.textContent).join('|') === 'buy milk'`));
  await b.evaluate(`${all('.filters a')}[0].click()`);
  await b.evaluate(`${q('.clear-completed')}.click()`);
  check('clear completed', await b.waitFor(`${all('.todo-list label')}.map(l => l.textContent).join('|') === 'write docs'`));
}
