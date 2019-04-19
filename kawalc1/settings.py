"""
Django settings for kawalc1 project.

Generated by 'django-admin startproject' using Django 2.0.13.

For more information on this file, see
https://docs.djangoproject.com/en/2.0/topics/settings/

For the full list of settings and their values, see
https://docs.djangoproject.com/en/2.0/ref/settings/
"""

import os
import pathlib


def __is_local():
    import socket
    host_name = socket.gethostname()
    print("local hostname: ", host_name)
    return False
    # return host_name.endswith(".local")

def patch_https_connection_pool(**constructor_kwargs):
    """
    This allows to override the default parameters of the
    HTTPConnectionPool constructor.
    For example, to increase the poolsize to fix problems
    with "HttpSConnectionPool is full, discarding connection"
    call this function with maxsize=16 (or whatever size
    you want to give to the connection pool)
    """
    from urllib3 import connectionpool, poolmanager

    class MyHTTPSConnectionPool(connectionpool.HTTPSConnectionPool):
        def __init__(self, *args, **kwargs):
            kwargs.update(constructor_kwargs)
            super(MyHTTPSConnectionPool, self).__init__(*args, **kwargs)

    poolmanager.pool_classes_by_scheme['https'] = MyHTTPSConnectionPool

patch_https_connection_pool(maxsize=256)


FORCE_LOCAL_FILE_SYSTEM = os.environ.get('FORCE_LOCAL_FILE_SYSTEM', False)
LOCAL = FORCE_LOCAL_FILE_SYSTEM or __is_local()
STORAGE_CLASS = 'django.core.files.storage.FileSystemStorage' if LOCAL else 'storages.backends.gcloud.GoogleCloudStorage'

import os

CREDS_FROM_FILE = os.environ.get('CREDS_FROM_FILE', False)
if CREDS_FROM_FILE:
    os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = "kawalc1-google-credentials.json"
SECRET = os.environ.get('KAWALC1_SECRET', 'test')
AUTHENTICATION_ENABLED = SECRET is not 'test'
BASE_DIR = "."
TARGET_EXTENSION = ".webp"
PADDING_INNER = 2
PADDING_OUTER = 8

import logging

mpl_logger = logging.getLogger('matplotlib')
mpl_logger.setLevel(logging.WARNING)

INMEMORYSTORAGE_PERSIST = True
STATIC_DIR = os.path.join(BASE_DIR, 'static')
VALIDATION_DIR = os.path.join(BASE_DIR, 'validation')
DATASET_DIR = os.path.join(STATIC_DIR, 'datasets')
CONFIG_FILE = os.path.join(DATASET_DIR, 'gubernur-jakarta.json')
CATEGORIES_COUNT = 11
GS_BUCKET_NAME = 'kawalc1'
GS_FILE_OVERWRITE = True
GS_DEFAULT_ACL = 'publicRead'
VERSION = 'v0.9.1-alpha'


# Quick-start development settings - unsuitable for production
# See https://docs.djangoproject.com/en/2.0/howto/deployment/checklist/

# SECURITY WARNING: keep the secret key used in production secret!
SECRET_KEY = '_146et)+93n!)efakmmt4%$@3^zng1c0nf=f)3hc@gw6p@yy4e'

# SECURITY WARNING: don't run with debug turned on in production!
DEBUG = __is_local()

ALLOWED_HOSTS = [
    '*'
]

# Application definition

INSTALLED_APPS = [
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
]

MIDDLEWARE = [
    'django.middleware.security.SecurityMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
    'kawalc1.authentication_middleware.AuthenticationMiddleware',
    'kawalc1.memory_usage.MemoryUsageMiddleware'
]

ROOT_URLCONF = 'kawalc1.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.debug',
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
            ],
        },
    },
]

WSGI_APPLICATION = 'kawalc1.wsgi.application'

# Database
# https://docs.djangoproject.com/en/2.0/ref/settings/#databases

DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.sqlite3',
        'NAME': os.path.join(BASE_DIR, 'db.sqlite3'),
    }
}

# Password validation
# https://docs.djangoproject.com/en/2.0/ref/settings/#auth-password-validators

AUTH_PASSWORD_VALIDATORS = [
    {
        'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator',
    },
    {
        'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator',
    },
]

# Internationalization
# https://docs.djangoproject.com/en/2.0/topics/i18n/

LANGUAGE_CODE = 'en-us'

TIME_ZONE = 'UTC'

USE_I18N = True

USE_L10N = True

USE_TZ = True

# Static files (CSS, JavaScript, Images)
# https://docs.djangoproject.com/en/2.0/howto/static-files/

STATIC_URL = '/static/'
STATIC_DIRS = 'static'
STATICFILES_DIRS = [
    STATIC_DIRS
]
