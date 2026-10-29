# Build and test entry points for the RDT-over-UDP project.
#
# There are no third-party runtime dependencies, so plain javac is enough and
# there is no build tool to explain at the viva. Requires a JDK on PATH.

JAVAC      := javac
JAVA       := java
SRC_DIR    := src/main/java
TEST_DIR   := src/test/java
BUILD_DIR  := build/classes
JAVAC_OPTS := -Xlint:all -d $(BUILD_DIR)

# Heap pinned so it never resizes mid-run; see the plan, section 8.
RUN_OPTS   := -Xms512m -Xmx512m
SOURCES    := $(shell find $(SRC_DIR) $(TEST_DIR) -name '*.java')
TESTS      := rdt.PacketTest rdt.ChannelTest rdt.NetEmSmokeTest rdt.SeqSpaceTest rdt.TimerWheelTest rdt.RttEstimatorTest rdt.GoBackNTest rdt.ReceiveBufferTest rdt.RunStatsTest rdt.TraceLogTest rdt.HandshakeTest rdt.SessionTest rdt.SelectiveRepeatTest

.PHONY: all build test test-harness experiments figures clean


# Everything the report needs that runs in minutes: the Java suites, the harness
# and analysis tests, and every figure redrawn from the committed results CSVs.
all: test test-harness figures

build:
	@mkdir -p $(BUILD_DIR)
	$(JAVAC) $(JAVAC_OPTS) $(SOURCES)

test: build
	@for t in $(TESTS); do \
		echo "== $$t"; \
		$(JAVA) -cp $(BUILD_DIR) $$t || exit 1; \
	done

# The full matrix: about a day of runs. Run it from a copy of the repository on
# WSL's own Linux filesystem (writing to /mnt/c or /mnt/d stalls the receiver), on
# an otherwise idle machine. It resumes after an interruption. Experiment 4 keeps
# traces for analysis/spurious.py.
experiments: build
	python3 analysis/run_matrix.py --config analysis/configs/exp4_rto.json --trace
	python3 analysis/run_matrix.py --config analysis/configs/exp1_loss.json
	python3 analysis/run_matrix.py --config analysis/configs/exp3_reorder.json
	python3 analysis/run_matrix.py --config analysis/configs/exp2_window.json
	python3 analysis/spurious.py results/.work/exp4_rto/traces --out results/exp4_spurious.csv

test-harness:
	python3 analysis/test_run_matrix.py
	python3 analysis/test_spurious.py

figures:
	python3 analysis/plots.py

clean:
	rm -rf build
