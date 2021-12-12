## Overview
I recently had to implement Cloud Armor which is a Web Application Firewall (WAF).
I thought it would be pretty straight forward and could do it in a
short period of time, but of course ended up taking a bit longer.

The original setup had a few Clojure services running off of App Engine; an API,
a single page application (SPA) as well as the company's marketing site.
We relied on App Engine custom domains and dispatch rules to route traffic to the
appropriate service. Finally, there were two CDN enabled GCS buckets for various
static resources. The buckets were already backend buckets connected to another
load balancer.

Current URL scheme which we need to preserve:
```
https://api.example.com # targets the api service
https://app.example.com # targets the app service
https://www.example.com # targets the marketing-site service
https://static.example.com # targets 2 different backend buckets
https://example.com # targets the marketing-site service
```

In order to introduce Cloud Armor we have to setup several resources in GCP
which is best done through something like Terraform or Pulumi; however, for
this post we'll do it with `gcloud`.

Here's an inventory of what we'll need:
* serverless network endpoint groups
* backend services
* url maps
* SSL policy
* static IP address
* SSL Certificate
* SSL Proxy
* Load balancer

Traffic will arrive at the load balancer which hands the request to the associated https proxy.
The https proxy takes care of TLS termination and consults the associated URL Map to
determine which backend service should handle the request. The https proxy will then
call the actual service.

Using App Engine, Cloud Run or Cloud Functions requires something called a serverless
network endpoint group (NEG). A NEG is a configuration object used to point to an
app engine service.

## Serverless Network Endpoint Groups
Since `www` and the naked domain `example.com` need to target the
`marketing-site` service we have to create 3 different NEGs.
If there were a 1:1 mapping between host and service we could have created
one NEG with the flag `--app-engine-url-mask=<service>.example.com`.
```shell
gcloud compute network-endpoint-groups create serverless-neg-api --region=us-east1 --network-endpoint-type=serverless --app-engine-service=api
gcloud compute network-endpoint-groups create serverless-neg-app --region=us-east1 --network-endpoint-type=serverless --app-engine-service=app
gcloud compute network-endpoint-groups create serverless-neg-www --region=us-east1 --network-endpoint-type=serverless --app-engine-service=marketing-site
```


## Backend Services
Setup the backend services the load balancer will forward requests to.
Three separate backend services are needed because each backend service can only
have one serverless NEG per region.

```shell
gcloud compute backend-services create backend-service-www --global --load-balancing-scheme=EXTERNAL
gcloud compute backend-services create backend-service-api --global --load-balancing-scheme=EXTERNAL
gcloud compute backend-services create backend-service-app --global --load-balancing-scheme=EXTERNAL

gcloud compute backend-services add-backend backend-service-www --global --network-endpoint-group=serverless-neg-www --network-endpoint-group-region=us-east1
gcloud compute backend-services add-backend backend-service-api --global --network-endpoint-group=serverless-neg-api --network-endpoint-group-region=us-east1
gcloud compute backend-services add-backend backend-service-app --global --network-endpoint-group=serverless-neg-app --network-endpoint-group-region=us-east1


gcloud compute backend-services update backend-service-api --protocol=HTTPS
gcloud compute backend-services update backend-service-app --protocol=HTTPS
gcloud compute backend-services update backend-service-www --protocol=HTTPS
```

## URL Map
This is a configuration file which tells the load balancer what hosts and/or paths to map
to what backend.  Create the url map and then import the full config from a file. Service
dispatch is primarily based on the host, but a request to
`https://static.example.com/private/foo.jpg` will also take into
account the path and dispatch to the `private-static-example-com` backend bucket.

NB. A default service or default backend bucket is required.

The `url-map-https.yaml` file looks like:
```yaml
kind: compute#urlMap
name: url-map-https
defaultService: https://www.googleapis.com/compute/v1/projects/example-dev/global/backendServices/backend-service-www
hostRules:
- hosts:
  - 'api.example.com'
  pathMatcher: api
- hosts:
  - 'app.example.com'
  pathMatcher: app
- hosts:
  - 'example.com'
  - 'www.example.com'
  pathMatcher: www
- hosts:
  - 'static.example.com'
  pathMatcher: static
pathMatchers:
- name: api
  defaultService: https://www.googleapis.com/compute/v1/projects/example-dev/global/backendServices/backend-service-api
- name: app
  defaultService: https://www.googleapis.com/compute/v1/projects/example-dev/global/backendServices/backend-service-app
- name: www
  defaultService: https://www.googleapis.com/compute/v1/projects/example-dev/global/backendServices/backend-service-www
- name: static
  defaultService: https://www.googleapis.com/compute/v1/projects/example-dev/global/backendBuckets/static-example-com
  pathRules:
  - paths:
    - /private/*
    service: https://www.googleapis.com/compute/v1/projects/example-dev/global/backendBuckets/private-static-example-com
```

```shell
gcloud compute url-maps create url-map-https --default-service backend-service-www
gcloud compute url-maps import url-map-https --source url-map-https.yaml --quiet
```

## SSL Policy
By default, the load balancer will accept TLS 1.0 and some weaker ciphers for the greatest
client compatibility, but for many businesses this is not acceptable. In fact, application
pen testing will flag the deprecated TLS versions and weak ciphers as an issue requiring remediation.
The below configuration will earn an A letter grade from the [SSL Server Test](https://www.ssllabs.com/ssltest/) site as of July 2021.
```shell
gcloud compute ssl-policies create ssl-policy --profile RESTRICTED --min-tls-version 1.2
```

## Static IP
Create the static IP and then get its value to input into DNS.
```shell
gcloud compute addresses create static-ip-ingress --ip-version=IPV4 --global
gcloud compute addresses describe static-ip-ingress --format="get(address)" --global
```

## Google Managed SSL Certificate
```shell
gcloud compute ssl-certificates create ssl-certificate-global --domains=example.com,www.example.com,static.example.com,app.example.com,api.example.com --global
```
Update DNS to point to the static IP which will be attached to the load balancer.
The certificate won't be provisioned until after DNS entries are updated, but you can
still reference the certificate in the subsequent commands.


## SSL Proxy
```shell
gcloud compute target-https-proxies create https-proxy --ssl-certificates=ssl-certificate-global --ssl-policy=ssl-policy --url-map=url-map-https
```

## Forwarding Rule
```shell
gcloud compute forwarding-rules create forwarding-rule-https --address=static-ip-ingress --target-https-proxy=https-proxy --global --ports=443
```


## Cloud Armor Security Policy
Policies are attached to backends. A policy can be shared with multiple backends. However, each backend can only have one security policy. You'll have to configure the security policy for
your needs, but the following is enought to activate Cloud Armor.
```shell
gcloud compute security-policies create security-policy-default

gcloud compute backend-services update backend-service-api --security-policy security-policy-default --global
gcloud compute backend-services update backend-service-app --security-policy security-policy-default --global
gcloud compute backend-services update backend-service-www --security-policy security-policy-default --global
```

## Prevent Cloud Armor Bypass
By default App Engine will directly handle ingress from everywhere including the version specific
URLs which bypasses Cloud Armor. App Engine service level ingress rules can be configured to allow
access only from within the VPC and from the load balancers.
```shell
gcloud app services update app --ingress=internal-and-cloud-load-balancing
gcloud app services update api --ingress=internal-and-cloud-load-balancing
gcloud app services update marketing-site --ingress=internal-and-cloud-load-balancing
```

## HTTPS Redirect
Without the https redirect, `http://example.com` will result in a GCP 404 page.
To fix this, create a url map for http traffic which will always perform a 301 redirect to the
https equivalent.

The `url-map-http.yaml` file looks like:
```yaml
kind: compute#urlMap
name: url-map-http
defaultUrlRedirect:
   redirectResponseCode: MOVED_PERMANENTLY_DEFAULT
   httpsRedirect: True
```

```shell
gcloud compute url-maps import url-map-http --source ./services/url-map-http.yaml
gcloud compute target-http-proxies create http-proxy --url-map=url-map-http --global
gcloud compute forwarding-rules create forwarding-rule-redirect-http --address=static-ip-ingress --target-http-proxy=http-proxy --global --ports=80
```

## Compress Responses
The standard App Engine frontend automatically gzipped responses, but now the application
server must provide a gzipped response. With Clojure Ring, there is `ring-gzip-middleware`.
For Pedestal, it must be added to the `::http/container-options` when configuring the
service map. The `org.eclipse.jetty.server.handler.gzip.GzipHandler` class will do all
the hard work.

```clojure
{::http/container-options
  {:context-configurator
    (fn [c]
      (let[gh (GzipHandler.)
           methods (into-array ["GET" "POST" "PUT" "PATCH" "DELETE"])]
        (.addIncludedMethods gh methods)
        (doto c
          (.setGzipHandler gh))))}}
```

## Closing

That should be everything. This is the preferred setup with App Engine instead of using
custom domains and dispatch rules.  This setup provides control over SSL policies and
lets you configure a WAF. SSL policies are configurable via App Engine directly, but
requires a GCP support plan, opening a support ticket and countless back and forth
over their ticketing system.  The App Engine team applies the changes perhaps once a
month. If there are any misunderstandings as was the case with my ticket two months elapsed
before the SSL policy was configured per our needs.

## Notes
You can check the status of the ssl certificate with `gcloud compute ssl-certificates list`.
Once it says `ACTIVE` for all your domains, try connecting to the sites.  It might take several
minutes for all the services and configurations to coalesce.

To delete a NEG use:
```shell
gcloud compute network-endpoint-groups delete serverless-neg-api --region=us-east1
```
The region must be specified since it was specified at creation time otherwise you'll see
an error saying that the NEG doesn't exist.


To delete a backend service specify the `--global` flag
`gcloud compute backend-services delete backend-service-www --global`
