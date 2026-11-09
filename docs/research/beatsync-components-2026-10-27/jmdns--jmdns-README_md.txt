<div align="center">
<img src="https://github.com/jmdns/jmdns/blob/main/src/site/resources/images/logos/jmdns.png" height="100" >

# JmDNS
</div>
<br>

[![Version](https://img.shields.io/maven-central/v/org.jmdns/jmdns?logo=apache-maven&style=flat-square)](https://search.maven.org/artifact/org.jmdns/jmdns)
[![License](https://img.shields.io/badge/License-Apache%202.0-yellow.svg?style=flat-square)](https://opensource.org/licenses/Apache-2.0)
[![Build](https://github.com/jmdns/jmdns/actions/workflows/maven.yml/badge.svg)](https://github.com/jmdns/jmdns/blob/main/.github/workflows/maven.yml)

This Java implementation of multicast DNS enables both service discovery and registration, and is fully compatible with Apple's Bonjour.

# Attribution
This library is licensed under the Apache License Version 2.0.
Please see the file [NOTICE.txt](NOTICE.txt).  

Arthur van Hoff
avh@strangeberry.com

Rick Blair
rickblair@mac.com

Kai Kreuzer
kai@openhab.org

# Usage

This is an implementation of multi-cast DNS in Java. It supports service discovery and service registration. It is fully
interoperable with Apple's Bonjour. 

## Sample Code for Service Registration

```java
import java.io.IOException;
import java.net.InetAddress;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public class ExampleServiceRegistration {

    public static void main(String[] args) throws InterruptedException {

        try {
            // Create a JmDNS instance
            JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost());

            // Register a service
            ServiceInfo serviceInfo = ServiceInfo.create("_http._tcp.local.", "example", 1234, "path=index.html");
            jmdns.registerService(serviceInfo);

            // Wait a bit
            Thread.sleep(25000);

            // Unregister all services
            jmdns.unregisterAllServices();

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
}
```


## Sample code for Service Discovery

```java
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

public class ExampleServiceDiscovery {

    private static class SampleListener implements ServiceListener {
        @Override
        public void serviceAdded(ServiceEvent event) {
            System.out.println("Service added: " + event.getInfo());
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            System.out.println("Service removed: " + event.getInfo());
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            System.out.println("Service resolved: " + event.getInfo());
        }
    }

    public static void main(String[] args) throws InterruptedException {
        try {
            // Create a JmDNS instance
            JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost());

            // Add a service listener
            jmdns.addServiceListener("_http._tcp.local.", new SampleListener());

            // Wait a bit
            Thread.sleep(30000);
        } catch (UnknownHostException e) {
            System.out.println(e.getMessage());
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
}
```

