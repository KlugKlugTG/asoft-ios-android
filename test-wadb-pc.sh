#!/bin/bash
# test-wadb-pc.sh: the app's adb code (AdbKey, Spake2, AdbPairing, AdbTls, AdbConnection) on the PC, no phone.
#   1. SPAKE2's M and N against the numbers in BoringSSL's source
#   2. the key: its certificate through openssl, its adb line against "adb pubkey"
#   3. the PC's real "adb pair" against the code's stand-in for the phone's pairing dialog, with the right code and
#      with a wrong one (adb's own SPAKE2, key derivation and packets are the other side: what the phone runs too)
#   4. the app's pairing against that stand-in, and its connection and shell against a stand-in for adbd
# Needs Android Studio's Java (gradle.properties), adb and openssl. Conscrypt's desktop jar is downloaded once into
# build/wadb-pc. "adb pair" notes the stand-in's id in ~/.android/adb_known_hosts.pb: the file is put back after.
cd "$(dirname "$0")" || exit 1
export MSYS_NO_PATHCONV=1
JB="$(cygpath -u "$(sed -n 's/^org.gradle.java.home=//p' gradle.properties | sed 's/\\\\/\\/g')")/bin"
W=build/wadb-pc
V=2.9.0
JAR=$W/conscrypt-openjdk-uber-$V.jar
mkdir -p $W/classes
[ -s $JAR ] || curl -sSL --max-time 300 -o $JAR https://repo1.maven.org/maven2/org/conscrypt/conscrypt-openjdk-uber/$V/conscrypt-openjdk-uber-$V.jar || exit 1
S=app/src/main/java/com/asoft/ios
"$JB/javac" -nowarn -d $W/classes -cp $JAR $S/AdbKey.java $S/Spake2.java $S/AdbPairing.java $S/AdbTls.java $S/AdbConnection.java \
    tools/wadb/PairTest.java || exit 1
run() { "$JB/java" -cp "$W/classes;$JAR" com.asoft.ios.PairTest "$@" | tr -d '\r'; }
fail=0
say() { if [ "$1" = 0 ]; then echo "OK      $2"; else echo "FAILED  $2"; fail=1; fi; }

# 1
want='M 31406539342727633121250288103050113562375374900226415211311216773867585644232 21177308356423958466833845032658859666296341766942662650232962324899758529114
N 49918732221787544735331783592030787422991506689877079631459872391322455579424 54629554431565467720832445949441049581317094546788069926228343916274969994000'
[ "$(run points)" = "$want" ]; say $? "SPAKE2: M and N are BoringSSL's points"

# 2
K=$W/key
rm -rf $K
line=$(run key $K)
openssl x509 -inform der -in $K/adbkey.crt -out $K/cert.pem 2>/dev/null &&
    openssl verify -CAfile $K/cert.pem $K/cert.pem >/dev/null 2>&1 &&
    openssl x509 -in $K/cert.pem -noout -text | grep -q "CA:TRUE"
say $? "the certificate: openssl reads it and its own signature holds"
openssl pkey -inform der -in $K/adbkey.pk8 -out $K/key.pem 2>/dev/null
[ "${line%% *}" = "$(adb pubkey $K/key.pem | tr -d '\r' | cut -d' ' -f1)" ] && [ ${#line} -gt 700 ]
say $? "the key's adb line is what \"adb pubkey\" makes of the same key"

# 3
HOSTS=~/.android/adb_known_hosts.pb
[ -f $HOSTS ] && cp -p $HOSTS $W/known_hosts.keep
pair() {    # pair TYPED: the stand-in waits for 482913
    # (not through run's pipe: the port has to be in the file while the stand-in still waits; it waits 30 s)
    "$JB/java" -cp "$W/classes;$JAR" com.asoft.ios.PairTest serve $K 482913 > $W/serve.txt 2>&1 &
    local port=
    for _ in $(seq 50); do
        port=$(sed -n 's/^port //p' $W/serve.txt | tr -d '\r')
        [ -n "$port" ] && break
        sleep 0.2
    done
    echo "no port" > $W/adb.txt
    [ -n "$port" ] && adb pair 127.0.0.1:$port "$1" 2>&1 | tr -d '\r' > $W/adb.txt
    wait
}
pair 482913
grep -q "Successfully paired to 127.0.0.1" $W/adb.txt &&
    [ "$(sed -n 's/^peer //p' $W/serve.txt | tr -d '\r' | cut -d' ' -f1)" = "$(adb pubkey "$(cygpath -w ~/.android/adbkey)" | tr -d '\r' | cut -d' ' -f1)" ]
say $? "\"adb pair\" with the right code: paired, and the stand-in got the PC's adb key ($(head -c 60 $W/adb.txt))"
pair 482914
! grep -q "Successfully" $W/adb.txt && grep -q "WrongCode" $W/serve.txt
say $? "\"adb pair\" with a wrong code: refused on both sides ($(head -c 60 $W/adb.txt))"
if [ -f $W/known_hosts.keep ]; then cp -p $W/known_hosts.keep $HOSTS; else rm -f $HOSTS; fi

# 4
run self $W/self > $W/self.txt 2>&1
[ "$(grep -c '  OK$' $W/self.txt)" = 3 ]; say $? "the app's side: $(tr '\n' ';' < $W/self.txt | cut -c1-300)"

[ $fail = 0 ] && echo "all passed" || echo "FAILED"
exit $fail
