#!/usr/bin/env python3
"""Whisper's encoder, made to listen to as much audio as was said rather than to thirty seconds.

Whisper's encoder is exported with a table of 1500 positions added to its input, one for every
twenty milliseconds of a thirty-second window, so it accepts thirty seconds and nothing shorter:
a word said in one second is padded to thirty, and the encoder, which is nearly all of the time
a question takes, does thirty seconds of work for it. This slices that table to the length of
the input instead, which is the only thing in the graph tied to the window. The weights are the
model's own and unchanged.

The panel then gives it ten seconds of audio for anything shorter, which measured as nearly as
accurate as thirty on one- and two-word questions and a third of the time
(src/engines/whisper.ts says why ten).

  nix shell --impure --expr 'with import <nixpkgs> {}; python3.withPackages (ps: [ps.onnx])' \\
    -c python3 tools/cut_whisper_encoder.py <encoder.onnx> <out.onnx>

The input is onnx-community/whisper-small's onnx/encoder_model_quantized.onnx at the revision
src/engines/whisper.ts pins. The output is published as an asset of the speech-v1 release.
"""
import sys

import onnx
from onnx import TensorProto, helper


def constant(name, values):
    return helper.make_node(
        "Constant", [], [name],
        value=helper.make_tensor(f"{name}/value", TensorProto.INT64, [len(values)], values))


def main():
    source, target = sys.argv[1], sys.argv[2]
    model = onnx.load(source)
    graph = model.graph
    # Where the positions are added: the one Add with the position table as an input.
    tables = {init.name for init in graph.initializer if list(init.dims)[:1] == [1500]}
    adding = [node for node in graph.node
              if node.op_type == "Add" and any(name in tables for name in node.input)]
    if len(adding) != 1:
        raise SystemExit(f"expected one Add of the position table, found {len(adding)}")
    add = adding[0]
    table = next(name for name in add.input if name in tables)
    hidden = next(name for name in add.input if name != table)
    # The positions as long as the input is: the table's first [length] rows.
    nodes = [
        helper.make_node("Shape", [hidden], ["cut/shape"]),
        constant("cut/zero", [0]),
        constant("cut/one", [1]),
        constant("cut/two", [2]),
        helper.make_node("Slice", ["cut/shape", "cut/one", "cut/two", "cut/zero"], ["cut/length"]),
        helper.make_node("Slice", [table, "cut/zero", "cut/length", "cut/zero"], ["cut/positions"]),
    ]
    at = list(graph.node).index(add)
    for offset, node in enumerate(nodes):
        graph.node.insert(at + offset, node)
    add.input[list(add.input).index(table)] = "cut/positions"
    onnx.checker.check_model(model)
    onnx.save(model, target)
    print(f"wrote {target}")


if __name__ == "__main__":
    main()
