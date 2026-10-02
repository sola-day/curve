export default async function (b, check) {
  const q = (s) => `document.querySelector(${JSON.stringify(s)})`;
  const all = (s) => `[...document.querySelectorAll(${JSON.stringify(s)})]`;
  const type = (sel, v, ev = 'input') => b.evaluate(`(() => { const i = ${q(sel)}; i.value = ${JSON.stringify(v)};
    i.dispatchEvent(new Event(${JSON.stringify(ev)}, {bubbles: true})); })()`);
  const login = async (pw) => {
    await b.waitFor(`${q('input.user')} !== null`);
    await type('input.user', 'admin'); await type('input.password', pw);
    await b.evaluate(`${q('button.login')}.click()`);
  };
  await login('wrong');
  check('wrong password shows error', await b.waitFor(`${q('.error')}.textContent !== ''`));
  await login('admin');
  check('login shows table', await b.waitFor(`${all('input.name')}.length >= 3`));
  const n = await b.evaluate(`${all('input.name')}.length`);
  await b.evaluate(`${q('button.add')}.click()`);
  check('add product', await b.waitFor(`${all('input.name')}.length === ${n + 1}`));
  await b.evaluate(`(() => { const i = ${all('input.name')}.at(-1); i.value = 'Durian';
    i.dispatchEvent(new Event('change', {bubbles: true})); })()`);
  await new Promise((r) => setTimeout(r, 300));
  await b.send('Page.reload');
  await login('admin');
  check('edit persisted across reload', await b.waitFor(`${all('input.name')}.at(-1)?.value === 'Durian'`));
  await b.evaluate(`${all('button.delete')}.at(-1).click()`);
  check('delete product', await b.waitFor(`${all('input.name')}.length === ${n}`));
  await b.evaluate(`${q('button.logout')}.click()`);
  check('logout', await b.waitFor(`${q('form.login')} !== null`));
}
