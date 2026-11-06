# YALog Family — piloto online

O painel e a API compartilham o mesmo domínio HTTPS. Este pacote inclui o
servidor e o APK de homologação Android 11+. Use dados fictícios no primeiro teste.

## Publicação

1. Use este repositório como origem. O [APK de homologação](YALog_Family_Homologacao.apk) está disponível para Android 11 ou superior. Para testar em Wi-Fi local, siga o [roteiro local](HOMOLOGACAO.md).
2. Em um provedor com Node.js 24, HTTPS gerenciado e disco persistente,
   crie um serviço web apontando para o repositório. Exemplo no Render:
   runtime Node, build `node --check server/server.mjs`, start `npm start`
   e health check `/healthz`. Use uma única instância.
3. Monte o volume persistente em `/var/data` e configure
   `YALOG_DB=/var/data/family.sqlite`, `YALOG_BIND=0.0.0.0` e
   `YALOG_MANAGED_HTTPS=1`. Use este último apenas atrás do proxy HTTPS
   gerenciado do provedor. O servidor usa o PORT informado pela hospedagem.
4. No shell da instância do serviço, execute `npm run invite` para gerar
   um convite de cadastro que vence em uma hora. Abra `/controle` no
   endereço HTTPS do serviço. Cadastre a conta e guarde o código de recuperação.
5. No painel, gere o código de pareamento. Instale o APK no aparelho de teste
   e informe a origem HTTPS (sem `/controle`) e o código. Teste um envio
   manual e confira localização, horário e resumo de uso no painel.

O banco e os convites ficam no ambiente de homologação. Guarde o APK de teste
e o convite de cadastro em canais separados. Em caso de troca de hospedagem,
o arquivo SQLite precisa ser transferido para preservar contas e vínculos.
