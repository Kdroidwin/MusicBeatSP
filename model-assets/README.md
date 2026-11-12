# Optional Home mood model assets

These files are downloaded only after the user opts into Home mood analysis.
The audio stays on the device.

## Music2Emo head

`music2emo-j-all-head.onnx` is an ONNX export of the `J_all.ckpt` checkpoint
from [AMAAI-Lab/Music2Emotion](https://github.com/AMAAI-Lab/Music2Emotion),
Hugging Face revision `b036e59471583c3d5b30c69e63e8c7323cc36c4a`. The model
repository identifies its license as Apache-2.0. Music2Emo is by Jaeyong Kang
and Dorien Herremans; see the project README and paper
https://arxiv.org/abs/2502.03979.

## MERT feature model

`music2emo-mert-layers-5-6.onnx` is a pruned ONNX derivative of the quantized
MERT-v1-95M model published by M-A-P. It exposes the two hidden states used by
Music2Emo and omits later transformer layers. MERT-v1-95M is licensed
CC-BY-NC-4.0; this model must only be used for non-commercial purposes, with
attribution and the license terms at
https://creativecommons.org/licenses/by-nc/4.0/ .

The application pins each download with a SHA-256 checksum. The Android port
uses Music2Emo's trained valence/arousal head and supplies its learned `N`
(unknown/no-chord) token plus the major-mode default. It does not run the
separate BTC chord recognizer, so the chord/key context used in the paper's
full pipeline is not present in this mobile port.
