"""Check the manifest against the SDK's setting rules and the keys ActionConfig and ActionItem read."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / 'kiosk-satellite-plugin.json').read_text(encoding='utf-8'))
SOURCES = {name: (ROOT / f'src/com/shymoose/ksactionbar/{name}.java').read_text(encoding='utf-8')
           for name in ('ActionConfig', 'ActionItem')}
TYPES = {'string', 'secret', 'boolean', 'number', 'color', 'select', 'entity', 'icon', 'dashboard', 'cameraView', 'list'}
CONDITION_TESTS = {'equals', 'notEquals', 'notEmpty'}


def check_fields(case, fields, nested=0):
    """Apply the SDK's rules to a level of settings or row fields."""
    keys = []
    for field in fields:
        with case.subTest(key=field['key']):
            case.assertRegex(field['key'], r'^[A-Za-z][A-Za-z0-9_]*$')
            case.assertIn(field['type'], TYPES)
            if nested:
                case.assertNotEqual(field['type'], 'secret')
            if field['type'] == 'select':
                case.assertLessEqual(len(field['options']), 32)
                if 'default' in field:
                    case.assertIn(field['default'], field['options'])
            if field['type'] == 'number' and 'default' in field:
                case.assertTrue(field['min'] <= field['default'] <= field['max'])
                case.assertEqual((field['default'] - field['min']) % field['step'], 0)
            if field['type'] == 'string' and 'default' in field:
                case.assertLessEqual(len(field['default']), 512)
            condition = field.get('visibleWhen')
            if condition:
                # The condition names a field declared earlier on the same level, with one test.
                case.assertIn(condition['key'], keys)
                case.assertEqual(len(set(condition) & CONDITION_TESTS), 1)
            if field['type'] == 'list':
                case.assertLess(nested, 2, 'lists nest one level at most')
                case.assertTrue(1 <= len(field['fields']) <= 16)
                case.assertLessEqual(field.get('maxItems', 32), 32)
                row = field.get('row', {})
                row_keys = {f['key'] for f in field['fields']}
                subtitle = row.get('subtitle', [])
                case.assertTrue(1 <= len([subtitle] if isinstance(subtitle, str) else subtitle) <= 4 or not subtitle)
                for key in [row.get('title'), row.get('icon'), *([row['subtitle']] if isinstance(row.get('subtitle'), str) else row.get('subtitle', []))]:
                    if key:
                        case.assertIn(key, row_keys)
                check_fields(case, field['fields'], nested + 1)
        keys.append(field['key'])
    case.assertEqual(len(keys), len(set(keys)))


class ManifestSchemaTest(unittest.TestCase):
    def test_settings_follow_sdk_limits(self):
        settings = MANIFEST['settings']
        self.assertLessEqual(len(settings), 50)
        for setting in settings:
            self.assertIn('default', setting)
        check_fields(self, settings)

    def test_descriptions_are_sentences(self):
        # Kiosk Satellite's own settings describe each one in a sentence that ends with a period.
        def walk(fields):
            for field in fields:
                if 'description' in field:
                    with self.subTest(key=field['key']):
                        self.assertTrue(field['description'].endswith('.'))
                        self.assertLessEqual(len(field['description']), 100)
                walk(field.get('fields', []))
        walk(MANIFEST['settings'])

    def test_keys_match_the_code(self):
        keys = {s['key'] for s in MANIFEST['settings']}
        for key in ('position', 'labels', 'hideOn', 'scale', 'opacity', 'collapse', 'autoCollapse', 'makeRoom', 'items'):
            self.assertIn(key, keys)
            self.assertIn(f'"{key}"', SOURCES['ActionConfig'])
        items = next(s for s in MANIFEST['settings'] if s['key'] == 'items')
        for field in items['fields']:
            self.assertIn(f'"{field["key"]}"', SOURCES['ActionItem'])
        max_items = int(re.search(r'static final int MAX_ITEMS = (\d+);', SOURCES['ActionConfig']).group(1))
        self.assertEqual(items['maxItems'], max_items)
        # Kiosk Satellite follows at most 16 entities for a plugin.
        self.assertLessEqual(max_items, 16)

    def test_select_options_are_the_ones_the_code_understands(self):
        by_key = {s['key']: s for s in MANIFEST['settings']}
        self.assertEqual(by_key['position']['options'], ['Right', 'Left', 'Top', 'Bottom'])
        self.assertEqual(by_key['labels']['options'], ['Always', 'Never'])
        self.assertEqual(by_key['collapse']['options'], ['Start visible', 'Start hidden', 'Off'])
        fallbacks = ('Right', 'Always', 'Start visible')
        for key in ('position', 'labels', 'collapse'):
            for option in by_key[key]['options']:
                if option not in fallbacks:
                    self.assertIn(f'"{option}"', SOURCES['ActionConfig'])
        for option in by_key['scale']['options']:
            self.assertRegex(option, r'^[0-9]+%$')

    def test_groups_order_existing_setting_groups(self):
        groups = [g['title'] for g in MANIFEST.get('groups', [])]
        used = [s.get('group') for s in MANIFEST['settings']]
        self.assertEqual(len(groups), len(set(groups)))
        for title in groups:
            self.assertIn(title, used)
        self.assertEqual(groups[0], used[0])

    def test_commands_and_capabilities(self):
        for command in MANIFEST['commands']:
            self.assertRegex(command['id'], r'^[a-z][a-zA-Z0-9]*$')
        self.assertEqual(set(MANIFEST['capabilities']), {'overlay', 'host.read', 'ha'})
        self.assertGreaterEqual(MANIFEST['minAndroidSdk'], 26)


if __name__ == '__main__':
    unittest.main()