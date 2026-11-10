#!/usr/bin/env python3
"""Generate Firebase options only for explicitly enabled Android builds."""
import argparse
import json
from pathlib import Path
import zipfile
from xml.etree import ElementTree


def read_values(config, package):
    clients = [client for client in config['client']
               if client['client_info']['android_client_info']['package_name'] == package]
    if len(clients) != 1:
        raise ValueError(f'Expected one Firebase client for Android package {package}')
    client = clients[0]
    project = config['project_info']
    values = {
        'PACKAGE_NAME': package,
        'APPLICATION_ID': client['client_info']['mobilesdk_app_id'],
        'API_KEY': client['api_key'][0]['current_key'],
        'PROJECT_ID': project['project_id'],
        'SENDER_ID': project['project_number'],
        'STORAGE_BUCKET': project.get('storage_bucket', ''),
    }
    for name, value in values.items():
        if not isinstance(value, str) or (not value and name != 'STORAGE_BUCKET'):
            raise ValueError(f'Missing or invalid Firebase {name}')
    return values


def generate_source(config, package):
    values = read_values(config, package)
    fields = '\n'.join(f'    static final String {name} = {json.dumps(value)};'
                       for name, value in values.items() if name == 'PACKAGE_NAME')
    return ('package org.chromium.chrome.browser.lemurx.firebase;\n\n'
            'final class FirebaseConfig {\n' + fields +
            '\n    private FirebaseConfig() {}\n}\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--resources', action='store_true')
    args = parser.parse_args()
    try:
        config = json.loads(Path(args.input).read_text())
        source = generate_source(config, args.package)
        values = read_values(config, args.package)
    except (KeyError, IndexError, ValueError) as error:
        parser.error(str(error))
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    if args.resources:
        resources = ElementTree.Element('resources')
        names = {'APPLICATION_ID': 'google_app_id', 'API_KEY': 'google_api_key',
                 'PROJECT_ID': 'project_id', 'SENDER_ID': 'gcm_defaultSenderId',
                 'STORAGE_BUCKET': 'google_storage_bucket'}
        for key, name in names.items():
            ElementTree.SubElement(resources, 'string', name=name,
                                   translatable='false').text = values[key]
        ElementTree.ElementTree(resources).write(args.output, encoding='utf-8',
                                                 xml_declaration=True)
        return
    with zipfile.ZipFile(args.output, 'w') as archive:
        archive.writestr(zipfile.ZipInfo(
            'org/chromium/chrome/browser/lemurx/firebase/FirebaseConfig.java'), source)


if __name__ == '__main__':
    main()
