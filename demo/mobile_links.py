"""Bounded public-page reader. Only called after explicit mobile consent.

Pin the validated public IP for each connection/redirect. A forwarded URL is
untrusted claim content, never automatically added to the evidence library.
"""
import http.client
import ipaddress
import re
import socket
import ssl
import json
from urllib.parse import urljoin, urlsplit, urlunsplit

from bs4 import BeautifulSoup

MAX_BYTES = 1_000_000
URL_PATTERN = re.compile(r"(?:https?://|www\.)[^\s<>]+", re.I)


def links_in(text):
    return list(dict.fromkeys(m.group(0).rstrip('.,;!?)]}') for m in URL_PATTERN.finditer(text)))[:2]


def link_count(text):
    """Count candidates before the two-link read cap; do not fetch extras."""
    return len(list(URL_PATTERN.finditer(text or "")))


def public_target(url, resolver=socket.getaddrinfo):
    if url.startswith('www.'):
        url = 'https://' + url
    if len(url) > 2048 or any(ord(c) < 32 for c in url) or '\\' in url:
        raise ValueError('This link cannot be opened safely.')
    parts = urlsplit(url)
    if parts.scheme not in ('http', 'https') or not parts.hostname or parts.username or parts.password:
        raise ValueError('Only public HTTP or HTTPS pages can be checked.')
    expected = 443 if parts.scheme == 'https' else 80
    if parts.port not in (None, expected):
        raise ValueError('Links using a nonstandard port are not opened.')
    host = parts.hostname.encode('idna').decode('ascii')
    addresses = resolver(host, expected, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
        raise ValueError('Private, local, and internal addresses are not opened.')
    return parts, host, expected, addresses[0][4][0]


class PinnedConnection(http.client.HTTPConnection):
    def __init__(self, host, port, address, secure):
        super().__init__(host, port, timeout=10)
        self.address, self.secure = address, secure

    def connect(self):
        sock = socket.create_connection((self.address, self.port), self.timeout)
        try:
            self.sock = ssl.create_default_context().wrap_socket(sock, server_hostname=self.host) if self.secure else sock
        except BaseException:
            sock.close()
            raise


def fetch_page(url):
    for _ in range(4):
        parts, host, port, address = public_target(url)
        target = urlunsplit(('', '', parts.path or '/', parts.query, ''))
        connection = PinnedConnection(host, port, address, parts.scheme == 'https')
        try:
            connection.request('GET', target, headers={'Host': host, 'User-Agent': 'ForwardCheckDemo/0.4 (public article reader)',
                'Accept': 'text/html,text/plain', 'Accept-Encoding': 'identity'})
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader('Location')
                if not location:
                    raise ValueError('This link has an incomplete redirect.')
                url = urljoin(url, location)
                continue
            if response.status != 200:
                raise ValueError('This public page is unavailable or requires a login.')
            content_type = response.getheader('Content-Type', '').lower()
            if not any(value in content_type for value in ('text/html', 'text/plain', 'application/xhtml+xml')):
                raise ValueError('This demo reads text pages, not images, videos, or downloads.')
            if response.getheader('Content-Encoding', 'identity') != 'identity':
                raise ValueError('This page uses an unsupported compressed response.')
            body = response.read(MAX_BYTES + 1)
            if len(body) > MAX_BYTES:
                raise ValueError('This page is too large for the bounded demo reader.')
        finally:
            connection.close()
        soup = BeautifulSoup(body, 'html.parser')
        title = soup.title.get_text(' ', strip=True)[:300] if soup.title else host
        published = None
        if 'html' in content_type:
            for selector in ('meta[property="article:published_time"]', 'meta[name="date"]', 'meta[itemprop="datePublished"]', 'time[datetime]'):
                date_node = soup.select_one(selector)
                if date_node:
                    published = date_node.get('content') or date_node.get('datetime')
                    if published:
                        break
            if not published:
                def find_date(value, depth=0):
                    if depth > 6: return None
                    if isinstance(value, dict):
                        if isinstance(value.get('datePublished'), str): return value['datePublished']
                        for child in value.values():
                            found = find_date(child, depth + 1)
                            if found: return found
                    elif isinstance(value, list):
                        for child in value[:20]:
                            found = find_date(child, depth + 1)
                            if found: return found
                    return None
                for tag in soup.find_all('script', type='application/ld+json')[:8]:
                    try: published = find_date(json.loads(tag.string or ''))
                    except (ValueError, TypeError): continue
                    if published: break
        for node in soup(['script', 'style', 'nav', 'header', 'footer', 'form', 'aside', 'noscript']):
            node.decompose()
        content = soup.find('article') or soup.find('main') or soup.body or soup
        blocks = [node.get_text(' ', strip=True) for node in content.find_all(['h1', 'h2', 'h3', 'p', 'li'])]
        text = '\n\n'.join(dict.fromkeys(value for value in blocks if len(value) >= 15))
        if not text:
            text = content.get_text(' ', strip=True)
        if len(text) < 30:
            raise ValueError('No readable article text was exposed by this page.')
        return {'url': url, 'title': title, 'text': text[:30000], 'truncated': len(text) > 30000,
                'published_at': published}
    raise ValueError('This link has too many redirects.')
