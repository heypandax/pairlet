"""Symbol gate classification: never exempt a real SDK by its name or stripped symbols."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    'codeless', Path(__file__).resolve().parents[1] / 'observability-codeless-framework.py')
codeless = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(codeless)

# Relevant otool fields from Xcode 26.2's actual /dev/null placeholder in a Release archive.
STUB = '''/archive/Frameworks/Sentry.framework/Sentry (architecture arm64):
Load command 0
      cmd LC_SEGMENT_64
  segname __TEXT
   nsects 1
Section
  sectname __text
   segname __TEXT
      addr 0x0000000000004000
      size 0x0000000000000000
Load command 1
      cmd LC_SEGMENT_64
  segname __LINKEDIT
   nsects 0
Load command 5
     cmd LC_SYMTAB
   nsyms 0
Load command 8
      cmd LC_BUILD_VERSION
 platform 2
    minos 100.0
      sdk 26.2
'''


class CodelessFrameworkTest(unittest.TestCase):
    def test_actual_xcode_stub_and_all_empty_architectures(self):
        self.assertTrue(codeless.is_codeless_stub(STUB))
        self.assertTrue(codeless.is_codeless_stub(STUB + STUB.replace('arm64', 'arm64e')))

    def test_stripped_sdk_with_code_is_not_exempt(self):
        self.assertFalse(codeless.is_codeless_stub(STUB.replace('size 0x0000000000000000', 'size 0x40')))

    def test_one_nonempty_slice_blocks_a_universal_binary(self):
        real_slice = STUB.replace('arm64', 'arm64e').replace('size 0x0000000000000000', 'size 0x40')
        self.assertFalse(codeless.is_codeless_stub(STUB + real_slice))

    def test_symbols_real_deployment_target_extra_sections_or_unknown_output_fail_closed(self):
        for text in (STUB.replace('nsyms 0', 'nsyms 2'), STUB.replace('minos 100.0', 'minos 15.0'),
                     STUB.replace('Load command 1', 'Section\n  sectname __const\n  segname __TEXT\n      size 0x20\nLoad command 1'),
                     '', 'error: malformed binary', STUB.replace('size 0x0000000000000000', 'size unknown'),
                     STUB.replace('Section\n', ''), STUB.replace('nsyms 0', '')):
            with self.subTest(text=text):
                self.assertFalse(codeless.is_codeless_stub(text))


if __name__ == '__main__':
    unittest.main()
