#!/usr/bin/env python3

import json
import unittest

import prepare_jmh_history as history


class PrepareJmhHistoryTest(unittest.TestCase):

    def test_known_benchmark_and_fixture_become_one_readable_series_name(self):
        result = history.transform_results([
            {
                "benchmark": (
                    "com.photographer.focusstack.cr3.benchmark."
                    "Cr3RawProcessingStagesBenchmark.demosaicBayer"
                ),
                "params": {"sample": "eosr-iso-low.cr3"},
                "primaryMetric": {"score": 12.5, "scoreUnit": "ms/op"},
            }
        ])

        self.assertEqual(
            "RAW processing · Demosaic Bayer data — EOS R · low ISO",
            result[0]["benchmark"],
        )
        self.assertNotIn("params", result[0])
        self.assertEqual(12.5, result[0]["primaryMetric"]["score"])

    def test_non_sample_parameters_are_rendered_without_raw_json(self):
        result = history.transform_results([
            {
                "benchmark": (
                    "com.photographer.focusstack.cr3.raw.codec."
                    "CrxDecoderMicroStagesBenchmark.decodeRiceSymbols"
                ),
                "params": {
                    "variant": "ones-until-zero",
                    "strict": True,
                },
            }
        ])

        self.assertEqual(
            "CRX inner loop · Decode Rice symbol — Strict: yes · Variant: ones-until-zero",
            result[0]["benchmark"],
        )
        self.assertNotIn("params", result[0])

    def test_unknown_benchmark_has_a_readable_fallback(self):
        result = history.transform_results([
            {
                "benchmark": "example.NewCodecBenchmark.decodeTilePayload",
                "params": {"sample": "future-camera.cr3"},
            }
        ])

        self.assertEqual(
            "New Codec · Decode Tile Payload — future-camera.cr3",
            result[0]["benchmark"],
        )

    def test_input_is_not_mutated(self):
        source = [{
            "benchmark": (
                "com.photographer.focusstack.cr3.benchmark."
                "Cr3BoxParseBenchmark.parse"
            ),
            "params": {"sample": "sample.cr3"},
        }]

        history.transform_results(source)

        self.assertEqual("sample.cr3", source[0]["params"]["sample"])
        self.assertTrue(source[0]["benchmark"].endswith(".parse"))

    def test_existing_data_js_is_migrated_without_losing_history(self):
        original = {
            "lastUpdate": 123,
            "repoUrl": "https://example.invalid/repo",
            "entries": {
                "Canon CR3 JMH Benchmarks": [
                    {
                        "commit": {"id": "old-commit"},
                        "date": 100,
                        "tool": "jmh",
                        "benches": [
                            {
                                "name": (
                                    "com.photographer.focusstack.cr3.benchmark."
                                    "Cr3BoxParseBenchmark.parse "
                                    "( {\"sample\":\"sample.cr3\"} )"
                                ),
                                "value": 2.5,
                                "unit": "ms/op",
                            }
                        ],
                    },
                    {
                        "commit": {"id": "newer-commit"},
                        "date": 200,
                        "tool": "jmh",
                        "benches": [
                            {
                                "name": (
                                    "Container · Parse ISO-BMFF boxes — "
                                    "PowerShot · baseline"
                                ),
                                "value": 2.1,
                                "unit": "ms/op",
                            }
                        ],
                    },
                ]
            },
        }
        document = "window.BENCHMARK_DATA = " + json.dumps(original) + ";\n"

        transformed = history.transform_data_js(document)
        transformed_twice = history.transform_data_js(transformed)
        payload = json.loads(
            transformed.removeprefix("window.BENCHMARK_DATA = ")
            .removesuffix(";\n")
        )
        benches = payload["entries"]["Canon CR3 JMH Benchmarks"]

        self.assertEqual(transformed, transformed_twice)
        self.assertEqual("old-commit", benches[0]["commit"]["id"])
        self.assertEqual(2.5, benches[0]["benches"][0]["value"])
        self.assertEqual(
            benches[0]["benches"][0]["name"],
            benches[1]["benches"][0]["name"],
        )

    def test_rejects_invalid_jmh_and_history_shapes(self):
        with self.assertRaisesRegex(ValueError, "JSON array"):
            history.transform_results({})
        with self.assertRaisesRegex(ValueError, "no benchmark name"):
            history.transform_results([{}])
        with self.assertRaisesRegex(ValueError, "params"):
            history.transform_results([
                {"benchmark": "example.SomeBenchmark.run", "params": []}
            ])
        with self.assertRaisesRegex(ValueError, "unsupported wrapper"):
            history.transform_data_js("const data = {}")
        with self.assertRaisesRegex(ValueError, "entries"):
            history.transform_history_data({})


if __name__ == "__main__":
    unittest.main()
