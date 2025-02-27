# Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

load("@os_info//:os_info.bzl", "is_linux", "is_windows")
load("//bazel_tools:testing.bzl", "create_daml_app_codegen", "create_daml_app_dar", "daml_lf_compatible", "sdk_platform_test")
load(
    "//bazel_tools/daml_script:daml_script.bzl",
    "daml_script_dar",
    "daml_script_test",
)
load(
    "//bazel_tools/daml_trigger:daml_trigger.bzl",
    "daml_trigger_dar",
    "daml_trigger_test",
)
load(
    "//bazel_tools/data_dependencies:data_dependencies.bzl",
    "data_dependencies_coins",
    "data_dependencies_upgrade_test",
)
load("//bazel_tools:versions.bzl", "versions")
load("//sandbox-migration:util.bzl", "migration_test")
load("//:versions.bzl", "platform_versions", "sdk_versions", "stable_versions")
load("@daml//bazel_tools:haskell.bzl", "da_haskell_binary")

config_setting(
    name = "ghci_data",
    define_values = {
        "ghci_data": "True",
    },
)

[
    sh_binary(
        name = "sandbox-with-postgres-{}".format(version),
        srcs = ["@//bazel_tools:sandbox-with-postgres.sh"],
        args = ["sandbox"],
        data = [
            "@//bazel_tools/client_server/with-postgres:with-postgres-exe",
            "@daml-sdk-{}//:daml".format(version),
        ],
        deps = ["@bazel_tools//tools/bash/runfiles"],
    )
    for version in platform_versions
]

[
    [
        create_daml_app_dar(sdk_version),
        create_daml_app_codegen(sdk_version),
    ]
    for sdk_version in sdk_versions
]

# Instead of testing the full cartesian product of all SDK versions with
# all platform (~= Sandbox/JSON API) versions, we test the latest version of
# each with all versions of the other. This gives us a reasonable feedback
# with regards to maintaining backwards-compatibility without causing the
# test runs to grow quadratically.
head = "0.0.0"

# Missing on purpose: do not test the latest SDK with the latest platform
# That is not a compatibility test, it's just testing the main branch. ;)

# Test all old platform versions with the latest SDK
[
    sdk_platform_test(
        platform_version = platform_version,
        sdk_version = head,
    )
    for platform_version in platform_versions
    if platform_version != head and daml_lf_compatible(head, platform_version)
]

# Test all old SDK versions with the latest platform
[
    sdk_platform_test(
        platform_version = head,
        sdk_version = sdk_version,
    )
    for sdk_version in sdk_versions
    if sdk_version != head and daml_lf_compatible(sdk_version, head)
]

[
    daml_script_dar(sdk_version)
    for sdk_version in sdk_versions
]

[
    daml_script_test(
        compiler_version = sdk_version,
        runner_version = platform_version,
    )
    for sdk_version in sdk_versions
    for platform_version in platform_versions
    # Test that the DAML script runner can run DARs built with an older SDK
    # version. I.e. where the runner version is at least the SDK version or
    # more recent.
    if versions.is_at_least(sdk_version, platform_version)
]

# Change to `CommandId` generation
first_post_7587_trigger_version = "1.7.0-snapshot.20201012.5405.0.af92198d"

[
    daml_trigger_dar(sdk_version)
    for sdk_version in sdk_versions
    if versions.is_at_least(first_post_7587_trigger_version, sdk_version)
]

[
    daml_trigger_test(
        compiler_version = sdk_version,
        runner_version = platform_version,
    )
    for sdk_version in sdk_versions
    for platform_version in platform_versions
    # Test that the DAML trigger runner can run DARs built with an older SDK
    # version. I.e. where the runner version is at least the SDK version or
    # more recent.
    if versions.is_at_least(first_post_7587_trigger_version, sdk_version) and
       versions.is_at_least(sdk_version, platform_version)
]

# We have two migration tests: migration-stable runs through all stable releases
# including current HEAD. migration-all includes snapshot releases.

migration_test(
    name = "migration-stable",
    timeout = "eternal",
    # Exclusive due to hardcoded postgres ports.
    tags = [
        "exclusive",
    ] +
    # These tests are fairly slow so on PRs we only run them on Linux
    (["head-quick"] if is_linux else []),
    versions = stable_versions,
) if not is_windows else None

migration_test(
    name = "meta-migration-test",
    tags = [
        "exclusive",
        "manual",
    ],
    versions = [
        "1.0.0",
        "0.0.0",
    ],
)

migration_test(
    name = "migration-all",
    timeout = "eternal",
    # Exclusive due to hardcoded postgres ports.
    tags = ["exclusive"],
    versions = platform_versions,
) if not is_windows else None

[
    data_dependencies_coins(
        sdk_version = sdk_version,
    )
    for sdk_version in sdk_versions
]

[
    data_dependencies_upgrade_test(
        new_sdk_version = new_sdk_version,
        old_sdk_version = old_sdk_version,
    )
    for old_sdk_version in sdk_versions
    for new_sdk_version in sdk_versions
    # Tests that we can build a package with a newer SDK version that has
    # data-dependencies on packages built with an older SDK version.
    if versions.is_at_least(old_sdk_version, new_sdk_version)
]
