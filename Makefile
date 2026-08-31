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
TESTS      := rdt.PacketTest rdt.ChannelTest rdt.NetEmSmokeTest

.PHONY: all build test experiments figures clean

all: test

build:
	@mkdir -p $(BUILD_DIR)
	$(JAVAC) $(JAVAC_OPTS) $(SOURCES)

test: build
	@for t in $(TESTS); do \
		echo "== $$t"; \
		$(JAVA) -cp $(BUILD_DIR) $$t || exit 1; \
	done

experiments: build
	python3 analysis/run_matrix.py

figures:
	python3 analysis/plots.py

clean:
	rm -rf build
