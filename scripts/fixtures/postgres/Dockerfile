# Historic schema-v1 Docker manifests cannot run on current Docker. Build pinned vendor sources.
FROM gcc:14 AS build
ARG VERSION
COPY source.tar.bz2 /tmp/source.tar.bz2
RUN mkdir /tmp/source && tar -xjf /tmp/source.tar.bz2 -C /tmp/source --strip-components=1 \
 && cd /tmp/source \
 && CFLAGS='-O0 -fcommon -Wno-error=incompatible-pointer-types -Wno-error=implicit-function-declaration -Wno-error=implicit-int -Wno-error=return-mismatch' \
 ./configure --prefix=/opt/pgsql --without-readline --without-zlib \
 && make -j4 && make install
FROM debian:trixie-slim
COPY --from=build /opt/pgsql /opt/pgsql
RUN chmod -R a+rX /opt/pgsql && useradd --create-home postgres && mkdir /data && chown postgres:postgres /data
COPY --chmod=755 entrypoint.sh /entrypoint.sh
USER postgres
ENV PATH=/opt/pgsql/bin:$PATH
EXPOSE 5432
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
