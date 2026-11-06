fetch('privacidade-texto.html', { cache: 'no-store' }).then(r => r.text()).then(t => { document.getElementById('policy').innerHTML = t; })
  .catch(() => { document.getElementById('policy').textContent = 'Não foi possível carregar o texto. Tente novamente.'; });
