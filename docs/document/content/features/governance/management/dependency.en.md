+++
title = "Third-party Components"
weight = 4
+++

Apache ShardingSphere uses SPI to load data to the config center and registry center and disable instances and databases. 
Currently, Apache ShardingSphere supports frequently used registry centers, Zookeeper, Etcd, Apollo and Nacos. 
In addition, by injecting them to ShardingSphere with SPI, users can use other third-party config and registry centers to enable databases governance.

|                                               | *Driver*                                             | *Version* | *Config Center* | *Registry Center* |
| --------------------------------------------- | ---------------------------------------------------- | --------- | --------------- | ----------------- |
| [Zookeeper](https://zookeeper.apache.org/)    | [Apache Curator](http://curator.apache.org/)         | 3.6.x     | Support         | Support           |
| [Etcd](https://etcd.io/)                      | [jetcd](https://github.com/etcd-io/jetcd)            | v3        | Support         | Support           |
