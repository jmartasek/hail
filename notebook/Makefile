include ../config.mk

TOKEN = $(shell cat /dev/urandom | LC_ALL=C tr -dc 'a-z0-9' | head -c 12)

NOTEBOOK_IMAGE := $(DOCKER_PREFIX)/notebook:$(TOKEN)
NOTEBOOK_NGINX_IMAGE := $(DOCKER_PREFIX)/notebook_nginx:$(TOKEN)

EXTRA_PYTHONPATH := ../hail/python:../gear:../web_common
PYTHON := PYTHONPATH=$${PYTHONPATH:+$${PYTHONPATH}:}$(EXTRA_PYTHONPATH) python3
BLACK := $(PYTHON) -m black . --line-length=120 --skip-string-normalization

.PHONY: check
check:
	$(PYTHON) -m flake8 notebook
	$(PYTHON) -m pylint --rcfile ../pylintrc notebook --score=n
	$(BLACK) --check --diff
	curlylint .
	bash ../check-sql.sh

.PHONY: build-notebook
build-notebook:
	$(MAKE) -C ../docker service-base
	python3 ../ci/jinja2_render.py '{"service_base_image":{"image":"'$$(cat ../docker/service-base-image-ref)'"}}' Dockerfile Dockerfile.out
	../docker-build.sh .. notebook/Dockerfile.out $(NOTEBOOK_IMAGE)

.PHONY: build-nginx
build-nginx:
	$(MAKE) -C ../docker hail-ubuntu
	python3 ../ci/jinja2_render.py '{"hail_ubuntu_image":{"image":"'$$(cat ../docker/hail-ubuntu-image-ref)'"}}' Dockerfile.nginx Dockerfile.nginx.out
	python3 ../ci/jinja2_render.py '{"deploy": $(DEPLOY), "default_ns": {"name": "$(NAMESPACE)"}}' nginx.conf nginx.conf.out
	../docker-build.sh . Dockerfile.nginx.out $(NOTEBOOK_NGINX_IMAGE)

.PHONY: build
build: build-notebook build-nginx

JINJA_ENVIRONMENT = '{"code":{"sha":"$(shell git rev-parse --short=12 HEAD)"},"deploy":$(DEPLOY),"notebook_image":{"image":"$(NOTEBOOK_IMAGE)"},"notebook_nginx_image":{"image":"$(NOTEBOOK_NGINX_IMAGE)"},"default_ns":{"name":"$(NAMESPACE)"},"notebook_database":{"user_secret_name":"sql-notebook-user-config"},"scope":"$(SCOPE)"}'

.PHONY: deploy
deploy: build
	! [ -z $(NAMESPACE) ]  # call this like: make deploy NAMESPACE=default
	E=$(JINJA_ENVIRONMENT) && \
	  python3 ../ci/jinja2_render.py $$E deployment.yaml deployment.yaml.out && \
	kubectl -n $(NAMESPACE) apply -f deployment.yaml.out
