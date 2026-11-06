# YALog Family — teste real em rede local

O notebook hospeda o painel e o servidor. O Android do dependente acessa o
servidor pelo mesmo Wi-Fi. Para o primeiro teste, use aparelhos de adultos e
contas fictícias. Um segundo celular pode abrir o painel, ou o responsável
pode usar o navegador do notebook para começar.

## 1. Inicie o servidor

Instale Node.js 24 e OpenSSL no notebook. No Windows, confirme que o
comando `openssl version` funciona no terminal. Se o OpenSSL estiver em
outro diretório, configure a variável `OPENSSL_BIN` com o caminho do executável.

Extraia o pacote e abra o terminal na pasta extraída. Consulte o IPv4 do
adaptador Wi-Fi com `ipconfig` no Windows. Substitua o IP de exemplo pelo
do notebook:

```
node server/local-pilot.mjs 192.168.1.42
```

Mantenha esse terminal aberto. Se o firewall pedir, permita acesso somente
em redes privadas. Use o endereço HTTPS mostrado no terminal. A rede Wi-Fi
precisa permitir a comunicação entre os aparelhos e o notebook.

O script gera o certificado de teste em `piloto-local/yalog-piloto-ca.crt`.
Importe **somente o arquivo .crt**, como autoridade certificadora, no
dispositivo que abre o painel e no Android do dependente. No Android,
procure por **Instalar certificado CA** nas configurações de segurança.
Use apenas o certificado que o próprio notebook gerou. As chaves privadas
ficam no notebook. Depois do teste, remova a CA dos aparelhos usados.

## 2. Crie a conta do responsável

Em outro terminal na mesma pasta, rode:

```
node server/create-invite.mjs
```

Abra a página **/controle** no endereço HTTPS do terminal. Em **Criar
conta ou recuperar acesso**, cadastre a conta com o código de cadastro,
guarde o código de recuperação e entre. No painel, gere um segundo código,
desta vez para vincular o aparelho do dependente.

## 3. Vincule o Android e rode os testes

Instale o APK de homologação no aparelho Android 11 ou superior. Abra
o YALog Family e informe o endereço HTTPS, incluindo a porta e sem
`/controle`, e o código de pareamento. Autorize cada recurso que for testar.

1. Faça um envio manual. Confira horário, localização e uso no painel.
2. Ative o envio automático e deixe o aparelho com a tela apagada.
   Confira a notificação, o envio e a pausa. O intervalo é aproximado.
3. Defina uma regra para um app instalado pelo usuário e sincronize.
   Ative **YALog Family · Limites** nas configurações de acessibilidade.
   Em alguns aparelhos, o Android exige que o usuário habilite as
   configurações restritas do aplicativo instalado manualmente.
4. Desvincule o aparelho e confirme que o painel remove o último resumo.

Ao terminar, encerre o servidor no notebook. O banco de homologação fica
na pasta onde o servidor foi iniciado. O README descreve os fluxos de
recuperação, exclusão e isolamento entre famílias.
