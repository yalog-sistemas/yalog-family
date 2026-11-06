const byId = id => document.getElementById(id);
const date = value => new Date(value).toLocaleString('pt-BR');
async function api(path, data) {
  const options = data === undefined ? {} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)
  };
  const response = await fetch('/api/' + path, options);
  const payload = await response.json();
  if (!response.ok) throw Object.assign(Error(payload.error), { status: response.status });
  return payload;
}
function view(logged) {
  byId('parent').hidden = !logged;
  byId('login').hidden = logged;
  if (logged) byId('recovery').textContent = '';
  if (!logged) {
    byId('devices').replaceChildren();
    byId('invite').textContent = '';
  }
}
function append(parent, tag, text) {
  const item = document.createElement(tag);
  item.textContent = text;
  parent.append(item);
  return item;
}
function renderRules(card, item) {
  append(card, 'h3', 'Regras de aplicativos');
  append(card, 'p', 'O Android recebe as regras no próximo envio. O controle precisa estar ativado no aparelho.');
  for (const rule of item.rules) {
    const row = append(card, 'p', rule.app + ' · ' + (rule.minutes ? rule.minutes + ' min/dia' : 'sem limite diário')
      + (rule.from ? ' · bloquear ' + rule.from + '–' + rule.to : ''));
    append(row, 'button', 'Remover').onclick = () => save(item.rules.filter(value => value.app !== rule.app));
  }
  const form = append(card, 'form', '');
  const apps = document.createElement('select');
  apps.setAttribute('aria-label', 'Aplicativo');
  const choices = (item.usage?.apps ?? []).filter(app => app.package);
  for (const app of choices) {
    const option = append(apps, 'option', app.label);
    option.value = app.package;
  }
  if (!choices.length) {
    append(card, 'p', 'Aguarde um resumo com aplicativos para cadastrar regras.');
    return;
  }
  form.append(apps);
  function field(text, type) {
    const label = append(form, 'label', text);
    const input = append(label, 'input', '');
    input.type = type;
    return input;
  }
  const minutes = field('Limite diário em minutos (0 desativa)', 'number');
  minutes.min = '0';
  minutes.max = '1440';
  minutes.value = '0';
  const from = field('Início do bloqueio', 'time');
  const to = field('Fim do bloqueio', 'time');
  append(form, 'button', 'Salvar regra');
  const save = rules => perform(async () => {
    await api('rules', {id: item.id, rules});
    await refresh();
  });
  form.onsubmit = event => {
    event.preventDefault();
    save([...item.rules.filter(rule => rule.app !== apps.value),
      {app: apps.value, minutes: Number(minutes.value), from: from.value, to: to.value}]);
  };
}
async function refresh() {
  const {devices} = await api('devices');
  view(true);
  const container = byId('devices');
  container.replaceChildren();
  if (!devices.length) append(container, 'p', 'Nenhum aparelho vinculado.');
  for (const item of devices) {
    const card = append(container, 'article', '');
    append(card, 'h2', item.label);
    append(card, 'p', item.updated ? 'Último envio: ' + date(item.updated) : 'Aguardando primeiro envio.');
    if (item.location) {
      const point = item.location;
      append(card, 'p', 'Posição de ' + date(point.at) + ' · precisão de ' + Math.round(point.accuracy) + ' m');
      if (Date.now() - point.at > 3600000) append(card, 'p', 'Posição registrada há mais de uma hora.');
      const link = append(card, 'a', 'Abrir posição no mapa');
      link.href = 'https://www.openstreetmap.org/?mlat=' + point.lat + '&mlon=' + point.lon
        + '#map=16/' + point.lat + '/' + point.lon;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
    } else append(card, 'p', 'Sem localização no último envio.');
    if (item.usage) {
      append(card, 'h3', 'Tempo estimado de uso · ' + item.usage.day);
      if (!item.usage.apps.length) append(card, 'p', 'Nenhum uso disponível.');
      const list = append(card, 'ul', '');
      for (const app of item.usage.apps) {
        append(list, 'li', app.label + ' · ' + Math.floor(app.seconds / 60) + ' min ' + app.seconds % 60 + ' s');
      }
    } else append(card, 'p', 'Sem resumo de uso no último envio.');
    renderRules(card, item);
    const revoke = append(card, 'button', 'Desvincular e apagar os dados');
    revoke.onclick = () => {
      if (confirm('Desvincular este aparelho e apagar o último envio?')) {
        perform(async () => {
          await api('revoke', {id: item.id});
          await refresh();
        });
      }
    };
  }
}
async function perform(action, initial = false) {
  byId('status').textContent = '';
  try {
    await action();
  } catch (error) {
    if (error.status === 401) view(false);
    if (!initial || error.status !== 401) byId('status').textContent = error.message;
  }
}
byId('login-form').onsubmit = event => {
  event.preventDefault();
  const login = byId('username').value;
  const password = byId('password').value;
  byId('password').value = '';
  perform(async () => {
    await api('login', {login, password});
    await refresh();
  });
};
byId('account-form').onsubmit = event => {
  event.preventDefault();
  const operation = byId('operation').value;
  const login = byId('account-user').value;
  const password = byId('account-password').value;
  const code = byId('account-code').value.trim();
  byId('account-password').value = '';
  byId('account-code').value = '';
  perform(async () => {
    const result = await api(operation, {login, password, code});
    byId('username').value = login;
    byId('recovery').textContent = 'Guarde este novo código de recuperação em local seguro. Ele é exibido uma vez.\n'
      + result.recovery + '\nDepois, entre com a sua senha.';
  });
};
byId('delete-form').onsubmit = event => {
  event.preventDefault();
  const password = byId('delete-password').value;
  byId('delete-password').value = '';
  if (!confirm('Excluir a conta e os dados de todos os aparelhos desta família?')) return;
  perform(async () => {
    await api('delete-family', {password});
    view(false);
    byId('status').textContent = 'Conta e dados excluídos do servidor.';
  });
};
byId('invite-form').onsubmit = event => {
  event.preventDefault();
  perform(async () => {
    const invite = await api('invite', {label: byId('label').value});
    byId('invite').textContent = 'Servidor: ' + location.origin + '\nCódigo: ' + invite.code
      + '\nVálido até ' + date(invite.expires) + '. Uso único.';
  });
};
byId('refresh').onclick = () => perform(refresh);
byId('logout').onclick = () => perform(async () => {
  await api('logout', {});
  view(false);
});
perform(refresh, true);
