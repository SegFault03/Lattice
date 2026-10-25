set -eu
initdb -D /data -E UTF8 --no-locale
printf '\nlisten_addresses = '\''*'\''\n' >> /data/postgresql.conf
# Disposable fixture, published only on loopback. Match modern image authentication tests.
printf '\nhost all all 0.0.0.0/0 md5\n' >> /data/pg_hba.conf
pg_ctl -D /data -o "-c listen_addresses=''" -w start
psql -U postgres -d postgres -c "ALTER USER postgres PASSWORD 'lattice-test'"
pg_ctl -D /data -m fast -w stop
exec postgres -D /data
