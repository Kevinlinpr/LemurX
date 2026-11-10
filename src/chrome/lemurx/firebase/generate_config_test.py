#!/usr/bin/env python3
"""Validate package selection and malformed Firebase configuration failures."""
import copy
import json
from pathlib import Path
import unittest

from generate_config import generate_source, read_values


class FirebaseConfigTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads(Path(__file__).with_name('google-services.json').read_text())
        self.package = 'com.osmangroupx.aero'

    def test_selects_matching_client_not_first(self):
        other = copy.deepcopy(self.config['client'][0])
        other['client_info']['android_client_info']['package_name'] = 'other.app'
        other['client_info']['mobilesdk_app_id'] = 'other-app-id'
        self.config['client'].insert(0, other)
        values = read_values(self.config, self.package)
        self.assertEqual(values['APPLICATION_ID'],
                         '1:498250877255:android:0e732bb4f04a4ca000a462')
        self.assertNotIn('other-app-id', generate_source(self.config, self.package))
        self.assertIn('com.osmangroupx.aero', generate_source(self.config, self.package))

    def test_rejects_wrong_package(self):
        with self.assertRaisesRegex(ValueError, 'Expected one Firebase client'):
            read_values(self.config, 'com.osmangroupx.aero.dev')

    def test_rejects_ambiguous_client(self):
        self.config['client'] *= 2
        with self.assertRaises(ValueError):
            read_values(self.config, self.package)

    def test_rejects_empty_api_key(self):
        self.config['client'][0]['api_key'][0]['current_key'] = ''
        with self.assertRaisesRegex(ValueError, 'API_KEY'):
            read_values(self.config, self.package)

    def test_optional_storage_bucket(self):
        del self.config['project_info']['storage_bucket']
        self.assertEqual(read_values(self.config, self.package)['STORAGE_BUCKET'], '')


if __name__ == '__main__':
    unittest.main()
