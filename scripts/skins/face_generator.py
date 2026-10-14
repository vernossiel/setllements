import argparse
import glob
import numpy as np
import os
import cv2
from scipy.ndimage import gaussian_filter
from tqdm import tqdm


def img_load(img_file):
    return cv2.imread(img_file, flags=cv2.IMREAD_UNCHANGED)


def convert_to_zombie(skin_file: str):
    image = img_load(skin_file)

    alpha = image[:, :, 3]
    alpha_blend = gaussian_filter(alpha, 1) * 0.5

    img = cv2.cvtColor(image[:, :, 0:3], cv2.COLOR_BGR2HSV).astype(float)

    if img[:, :, 0].mean() > 128:
        img[:, :, 0] = img[:, :, 0] * 0.1 + 180 * 0.9
    else:
        img[:, :, 0] = img[:, :, 0] * 0.1

    img[:, :, 1] *= 1.5
    img[:, :, 2] *= 0.75

    img = np.minimum(255, img * np.stack((alpha,) * 3, axis=2) / 255).astype(np.uint8)

    image[:, :, 0:3] = cv2.cvtColor(img, cv2.COLOR_HSV2BGR)
    image[:, :, 3] = np.maximum(alpha, alpha_blend)

    return image.astype(np.uint8)


if __name__ == "__main__":
    parser = argparse.ArgumentParser("Generate burnt and zombie clothes")
    parser.add_argument(
        "--path",
        help="Path to the clothes",
        type=str,
        default="../../common/src/main/resources/assets/mca/skins/face/",
    )
    args = parser.parse_args()

    files_source = glob.glob(os.path.join(args.path, "normal/*/*.png"))
    files_zombie = glob.glob(os.path.join(args.path, "zombie/*/*.png"))

    for file in tqdm(files_source):
        zombie_file = file.replace("normal", "zombie")

        # convert
        img = convert_to_zombie(file)

        # writes
        os.makedirs(os.path.dirname(zombie_file), exist_ok=True)
        cv2.imwrite(zombie_file, img)
