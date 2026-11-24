#!/bin/sh
# Cria a identidade local de assinatura de código "Arara Dev Local" no chaveiro de login.
#
# Por quê: o Arara é assinado ad-hoc, e o macOS amarra as permissões (Acessibilidade, Microfone)
# à assinatura do app. Como o ad-hoc muda a cada build, todo rebuild/reinstalação quebra o atalho
# até a permissão ser concedida de novo. Com um certificado próprio e estável a assinatura não
# muda entre builds e a permissão sobrevive.
#
# Uso: instalador/macos/criar_cert_local.sh [nome da identidade]
# O nome padrão é o que a tarefa macApp do Gradle procura; com outro nome, build com
# ./gradlew :app:dmg -PassinaturaMac="Nome Escolhido".
set -eu

nome="${1:-Arara Dev Local}"

if security find-certificate -c "$nome" ~/Library/Keychains/login.keychain-db >/dev/null 2>&1; then
    echo "Já existe um certificado \"$nome\" no chaveiro. Nada a fazer."
    exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

openssl req -new -newkey rsa:2048 -nodes \
    -keyout "$tmp/chave.pem" -out "$tmp/pedido.csr" -subj "/CN=$nome"
printf 'basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=critical,codeSigning\n' \
    > "$tmp/extensoes.txt"
openssl x509 -req -in "$tmp/pedido.csr" -signkey "$tmp/chave.pem" \
    -out "$tmp/certificado.crt" -days 3650 -extfile "$tmp/extensoes.txt"
# Senha de descarte: o .p12 é apagado na saída (trap acima); o macOS exige senha não vazia na importação.
# PBE-SHA1-3DES + MAC sha1: o security do macOS não lê o PKCS12 moderno (PBES2/AES) do OpenSSL 3.
openssl pkcs12 -export -out "$tmp/identidade.p12" \
    -inkey "$tmp/chave.pem" -in "$tmp/certificado.crt" -passout pass:arara \
    -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1

# -A: qualquer aplicativo (o codesign) usa a chave sem o aviso "quer usar informação confidencial".
security import "$tmp/identidade.p12" -k ~/Library/Keychains/login.keychain-db -P arara -A

echo
echo "Identidade \"$nome\" criada no chaveiro de login (validade de 10 anos)."
echo "Confira com: security find-certificate -c \"$nome\""
echo "O próximo ./gradlew :app:dmg já assina com ela."
echo "Nota: por ser autoassinado, o security find-identity -v não o lista (não é 'válido' para o"
echo "sistema), mas o codesign o encontra e a assinatura fica estável — é o que importa."
