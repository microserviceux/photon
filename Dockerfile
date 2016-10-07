FROM clojure
COPY . /muon/photon
WORKDIR /muon/photon
COPY resources/config.properties.vagrant /muon/photon/config.properties
ENTRYPOINT ["java"]
CMD ["-jar", "target/photon.jar"]
