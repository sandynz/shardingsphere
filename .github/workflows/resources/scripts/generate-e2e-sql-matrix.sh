#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Usage: generate-e2e-sql-matrix.sh '<json-with-all-18-filter-labels>'
# Output: writes smoke-matrix, full-matrix, matrix (alias for full-matrix),
#         has-jobs, and need-proxy-image to $GITHUB_OUTPUT.
# smoke-matrix always contains only the 6 fixed smoke scenarios (no extra include rule).
# full-matrix follows the existing dynamic logic and includes the extra passthrough job.

set -euo pipefail

FILTERS_JSON="$1"

get_filter() {
  echo "$FILTERS_JSON" | jq -r ".$1"
}

# Read all 18 filter labels
adapter_proxy=$(get_filter adapter_proxy)
adapter_jdbc=$(get_filter adapter_jdbc)
mode_standalone=$(get_filter mode_standalone)
mode_cluster=$(get_filter mode_cluster)
mode_core=$(get_filter mode_core)
database_mysql=$(get_filter database_mysql)
database_postgresql=$(get_filter database_postgresql)
feature_sharding=$(get_filter feature_sharding)
feature_encrypt=$(get_filter feature_encrypt)
feature_readwrite_splitting=$(get_filter feature_readwrite_splitting)
feature_shadow=$(get_filter feature_shadow)
feature_mask=$(get_filter feature_mask)
feature_broadcast=$(get_filter feature_broadcast)
feature_distsql=$(get_filter feature_distsql)
feature_sql_federation=$(get_filter feature_sql_federation)
core_infra=$(get_filter core_infra)
test_framework=$(get_filter test_framework)
pom_changes=$(get_filter pom_changes)

ALL_SCENARIOS=$(jq -cn '[
  "empty_rules", "distsql_rdl", "passthrough",
  "db", "tbl", "encrypt", "readwrite_splitting",
  "shadow", "mask",
  "dbtbl_with_readwrite_splitting",
  "dbtbl_with_readwrite_splitting_and_encrypt",
  "sharding_and_encrypt", "encrypt_and_readwrite_splitting",
  "encrypt_shadow", "readwrite_splitting_and_shadow",
  "sharding_and_shadow", "sharding_encrypt_shadow",
  "mask_encrypt", "mask_sharding", "mask_encrypt_sharding",
  "db_tbl_sql_federation"
]')

ALL_ADAPTERS='["proxy","jdbc"]'
ALL_MODES='["Standalone","Cluster"]'
ALL_DATABASES='["MySQL","PostgreSQL"]'
FIXED_SMOKE_SCENARIOS='["empty_rules","db","tbl","encrypt","readwrite_splitting","passthrough"]'

# Build matrix JSON from dimension arrays and scenarios, applying exclude/include rules.
# $5 (add_extra): pass "true" to include the extra passthrough connector-version job,
#                 or "false" to omit it (used for smoke-matrix).
build_matrix() {
  local adapters="$1"
  local modes="$2"
  local databases="$3"
  local scenarios="$4"
  local add_extra="${5:-true}"

  jq -cn \
    --argjson adapters "$adapters" \
    --argjson modes "$modes" \
    --argjson databases "$databases" \
    --argjson scenarios "$scenarios" \
    --argjson add_extra "$add_extra" \
    '
    def should_exclude(adapter; mode; scenario):
      (adapter == "jdbc" and scenario == "passthrough") or
      (adapter == "jdbc" and mode == "Cluster") or
      (adapter == "proxy" and mode == "Standalone" and
        (scenario == "empty_rules" or scenario == "distsql_rdl" or scenario == "passthrough"));

    [
      $adapters[] as $adapter |
      $modes[] as $mode |
      $databases[] as $database |
      $scenarios[] as $scenario |
      select(should_exclude($adapter; $mode; $scenario) | not) |
      {adapter: $adapter, mode: $mode, database: $database, scenario: $scenario, "additional-options": ""}
    ] as $base_jobs |

    ([$base_jobs[] | select(.adapter == "proxy" and .mode == "Cluster")] | length > 0) as $has_proxy_cluster |
    ($scenarios | map(select(. == "passthrough")) | length > 0) as $has_passthrough |

    (if ($add_extra and $has_proxy_cluster and $has_passthrough)
     then [{adapter:"proxy", mode:"Cluster", database:"MySQL", scenario:"passthrough", "additional-options":"-Dmysql-connector-java.version=8.3.0"}]
     else []
     end) as $extra_job |

    {include: ($base_jobs + $extra_job)}
    '
}

# Determine whether a full fallback is required (all scenarios, all dimensions)
full_fallback=false
if [ "$core_infra" = "true" ] || [ "$test_framework" = "true" ] || [ "$pom_changes" = "true" ]; then
  full_fallback=true
fi

# Check whether any relevant dimension changed at all
any_relevant_change=false
if [ "$feature_sharding" = "true" ] || [ "$feature_encrypt" = "true" ] || \
   [ "$feature_readwrite_splitting" = "true" ] || [ "$feature_shadow" = "true" ] || \
   [ "$feature_mask" = "true" ] || [ "$feature_broadcast" = "true" ] || \
   [ "$feature_distsql" = "true" ] || [ "$feature_sql_federation" = "true" ] || \
   [ "$mode_standalone" = "true" ] || [ "$mode_cluster" = "true" ] || [ "$mode_core" = "true" ] || \
   [ "$database_mysql" = "true" ] || [ "$database_postgresql" = "true" ] || \
   [ "$adapter_proxy" = "true" ] || [ "$adapter_jdbc" = "true" ]; then
  any_relevant_change=true
fi

EMPTY='{"include":[]}'

if [ "$full_fallback" = "false" ] && [ "$any_relevant_change" = "false" ]; then
  {
    echo "smoke-matrix=$EMPTY"
    echo "full-matrix=$EMPTY"
    echo "matrix=$EMPTY"
    echo "has-jobs=false"
    echo "need-proxy-image=false"
  } >> "$GITHUB_OUTPUT"
  echo "::notice::No relevant changes detected. smoke-matrix: 0 jobs | full-matrix: 0 jobs"
  exit 0
fi

# Determine dimensions (adapters, modes, databases)
if [ "$full_fallback" = "true" ]; then
  adapters="$ALL_ADAPTERS"
  modes="$ALL_MODES"
  databases="$ALL_DATABASES"
  scenarios_json=$(echo "$ALL_SCENARIOS" | jq -c .)
else
  # Determine adapters
  if [ "$adapter_proxy" = "true" ] && [ "$adapter_jdbc" = "false" ]; then
    adapters='["proxy"]'
  elif [ "$adapter_jdbc" = "true" ] && [ "$adapter_proxy" = "false" ]; then
    adapters='["jdbc"]'
  else
    adapters="$ALL_ADAPTERS"
  fi

  # Determine modes
  if [ "$mode_standalone" = "true" ] && [ "$mode_cluster" = "false" ] && [ "$mode_core" = "false" ]; then
    modes='["Standalone"]'
  elif [ "$mode_cluster" = "true" ] && [ "$mode_standalone" = "false" ] && [ "$mode_core" = "false" ]; then
    modes='["Cluster"]'
  else
    modes="$ALL_MODES"
  fi

  # Determine databases
  if [ "$database_mysql" = "true" ] && [ "$database_postgresql" = "false" ]; then
    databases='["MySQL"]'
  elif [ "$database_postgresql" = "true" ] && [ "$database_mysql" = "false" ]; then
    databases='["PostgreSQL"]'
  else
    databases="$ALL_DATABASES"
  fi

  # Determine scenarios from feature labels
  any_feature_triggered=false
  scenarios_set=()

  add_scenario() {
    local s="$1"
    for existing in "${scenarios_set[@]+"${scenarios_set[@]}"}"; do
      [ "$existing" = "$s" ] && return
    done
    scenarios_set+=("$s")
  }

  if [ "$feature_sharding" = "true" ]; then
    any_feature_triggered=true
    for s in db tbl dbtbl_with_readwrite_splitting dbtbl_with_readwrite_splitting_and_encrypt \
              sharding_and_encrypt sharding_and_shadow sharding_encrypt_shadow \
              mask_sharding mask_encrypt_sharding db_tbl_sql_federation; do
      add_scenario "$s"
    done
  fi

  if [ "$feature_encrypt" = "true" ]; then
    any_feature_triggered=true
    for s in encrypt dbtbl_with_readwrite_splitting_and_encrypt sharding_and_encrypt \
              encrypt_and_readwrite_splitting encrypt_shadow sharding_encrypt_shadow \
              mask_encrypt mask_encrypt_sharding; do
      add_scenario "$s"
    done
  fi

  if [ "$feature_readwrite_splitting" = "true" ]; then
    any_feature_triggered=true
    for s in readwrite_splitting dbtbl_with_readwrite_splitting \
              dbtbl_with_readwrite_splitting_and_encrypt encrypt_and_readwrite_splitting \
              readwrite_splitting_and_shadow; do
      add_scenario "$s"
    done
  fi

  if [ "$feature_shadow" = "true" ]; then
    any_feature_triggered=true
    for s in shadow encrypt_shadow readwrite_splitting_and_shadow sharding_and_shadow \
              sharding_encrypt_shadow; do
      add_scenario "$s"
    done
  fi

  if [ "$feature_mask" = "true" ]; then
    any_feature_triggered=true
    for s in mask mask_encrypt mask_sharding mask_encrypt_sharding; do
      add_scenario "$s"
    done
  fi

  if [ "$feature_distsql" = "true" ]; then
    any_feature_triggered=true
    add_scenario "distsql_rdl"
  fi

  if [ "$feature_sql_federation" = "true" ]; then
    any_feature_triggered=true
    add_scenario "db_tbl_sql_federation"
  fi

  if [ "$feature_broadcast" = "true" ]; then
    any_feature_triggered=true
    add_scenario "empty_rules"
  fi

  # If no feature triggered, use the fixed smoke scenario set as the default
  if [ "$any_feature_triggered" = "false" ]; then
    scenarios_json="$FIXED_SMOKE_SCENARIOS"
  else
    scenarios_json=$(printf '%s\n' "${scenarios_set[@]}" | jq -R . | jq -sc .)
  fi
fi

# Build smoke matrix: always the fixed 6 scenarios, no extra passthrough job
SMOKE_MATRIX=$(build_matrix "$adapters" "$modes" "$databases" "$FIXED_SMOKE_SCENARIOS" false)

# Build full matrix: dynamic scenarios, with extra passthrough connector-version job
FULL_MATRIX=$(build_matrix "$adapters" "$modes" "$databases" "$scenarios_json" true)

SMOKE_COUNT=$(echo "$SMOKE_MATRIX" | jq '.include | length')
FULL_COUNT=$(echo "$FULL_MATRIX" | jq '.include | length')

echo "::notice::smoke-matrix: $SMOKE_COUNT jobs | full-matrix: $FULL_COUNT jobs | full_fallback=$full_fallback | adapters=$adapters | modes=$modes | databases=$databases"
echo "::debug::smoke-matrix: $(echo "$SMOKE_MATRIX" | jq -c .)"
echo "::debug::full-matrix: $(echo "$FULL_MATRIX" | jq -c .)"

HAS_JOBS=false
if [ "$FULL_COUNT" -gt 0 ]; then
  HAS_JOBS=true
fi

HAS_PROXY_SMOKE=$(echo "$SMOKE_MATRIX" | jq '[.include[] | select(.adapter == "proxy")] | length > 0')
HAS_PROXY_FULL=$(echo "$FULL_MATRIX" | jq '[.include[] | select(.adapter == "proxy")] | length > 0')
NEED_PROXY=false
if [ "$HAS_PROXY_SMOKE" = "true" ] || [ "$HAS_PROXY_FULL" = "true" ]; then
  NEED_PROXY=true
fi

{
  echo "smoke-matrix=$(echo "$SMOKE_MATRIX" | jq -c .)"
  echo "full-matrix=$(echo "$FULL_MATRIX" | jq -c .)"
  echo "matrix=$(echo "$FULL_MATRIX" | jq -c .)"
  echo "has-jobs=$HAS_JOBS"
  echo "need-proxy-image=$NEED_PROXY"
} >> "$GITHUB_OUTPUT"
