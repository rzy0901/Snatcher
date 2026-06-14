import os
import re
import glob
import pandas as pd
from test_new_power import get_db_top


def extract_connection_duration(metadata_path: str) -> float:
    """Extract the connection-establishment duration (seconds) from a metadata txt file."""
    with open(metadata_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # match e.g. "Connection Establishment Duration: 2203ms (2.203s)"
    match = re.search(r'Connection Establishment Duration:\s*\d+ms\s*\(([\d.]+)s\)', content)
    if match:
        return float(match.group(1))
    else:
        raise ValueError(f"could not extract connection duration from {metadata_path}")


def process_folder(folder_path: str, distance_m: int, reference_wav: str = "./model2.wav") -> list:
    """Process one distance folder and return a list of records."""
    results = []

    # find all right-channel PCM files
    pcm_pattern = os.path.join(folder_path, "audio_right_*.pcm")
    pcm_files = glob.glob(pcm_pattern)

    print(f"\nprocessing {folder_path} (distance={distance_m}m), found {len(pcm_files)} right PCM files")

    for pcm_file in pcm_files:
        # matching metadata txt file
        metadata_file = pcm_file.replace(".pcm", "_metadata.txt")

        if not os.path.exists(metadata_file):
            print(f"  warning: metadata file not found: {metadata_file}")
            continue

        try:
            # extract the connection duration
            connection_duration = extract_connection_duration(metadata_file)

            # compute the sound level (dB)
            db_value = get_db_top(pcm_file, reference_wav_file=reference_wav, threshold_ratio=0.1)
            # add the 100 dB calibration offset
            db_value += 100

            results.append({
                "distance_m": distance_m,
                "sound_level_dB": round(db_value, 2),
                "connection_duration_s": connection_duration
            })

            print(f"  {os.path.basename(pcm_file)}: dB={db_value:.2f}, duration={connection_duration}s")

        except Exception as e:
            print(f"  error processing {pcm_file}: {e}")

    return results


def find_distance_folders(base_dir: str) -> list:
    """Auto-detect all distance folders (e.g. 0m, 10m, 20m, ...)."""
    distance_folders = []

    if not os.path.exists(base_dir):
        return distance_folders

    for item in os.listdir(base_dir):
        folder_path = os.path.join(base_dir, item)
        if os.path.isdir(folder_path):
            # match folder names of the form "<number>m"
            match = re.match(r'^(\d+)m$', item)
            if match:
                distance = int(match.group(1))
                distance_folders.append((distance, folder_path))

    # sort by distance
    distance_folders.sort(key=lambda x: x[0])
    return distance_folders


if __name__ == "__main__":
    # configuration
    base_dir = "acoustic_dis"
    output_csv = "sound_distance.csv"
    reference_wav = "./model2.wav"

    all_results = []

    # auto-detect all distance folders
    distance_folders = find_distance_folders(base_dir)
    print(f"detected {len(distance_folders)} distance folders: {[f'{d}m' for d, _ in distance_folders]}")

    # process each distance folder
    for distance, folder_path in distance_folders:
        results = process_folder(folder_path, distance_m=distance, reference_wav=reference_wav)
        all_results.extend(results)

    # save to CSV
    df = pd.DataFrame(all_results)
    df.to_csv(output_csv, index=False)

    print(f"\n===== done =====")
    print(f"processed {len(all_results)} records")
    print(f"saved to: {output_csv}")
    print(df)
