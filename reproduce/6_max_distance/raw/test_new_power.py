import numpy as np
from scipy import signal
from scipy.signal import find_peaks
import wave
from typing import Dict
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.lines import Line2D


def read_wav(file_path: str) -> tuple[np.ndarray, int]:
    """Read WAV file and return audio data and sample rate"""
    try:
        with wave.open(file_path, 'rb') as wav_file:
            n_channels, sampwidth, sample_rate, n_frames = wav_file.getparams()[:4]
            audio_data = wav_file.readframes(n_frames)
            if sampwidth == 1:
                dtype = np.uint8
            elif sampwidth == 2:
                dtype = np.int16
            elif sampwidth == 4:
                dtype = np.int32
            else:
                raise ValueError(f"Unsupported sample width: {sampwidth}")
            audio_data = np.frombuffer(audio_data, dtype=dtype)
            if n_channels > 1:
                audio_data = audio_data.reshape(-1, n_channels)[:, 0]
            if dtype == np.uint8:
                audio_data = audio_data.astype(np.int16) - 128
            return audio_data, sample_rate
    except FileNotFoundError:
        raise FileNotFoundError(f"File not found: {file_path}")
    except Exception as e:
        raise Exception(f"Error reading WAV file: {e}")


def read_pcm(file_path: str, bit_depth: int = 16) -> np.ndarray:
    """Read PCM file and return audio data"""
    try:
        if bit_depth == 16:
            dtype = np.int16
        elif bit_depth == 8:
            dtype = np.int8
        else:
            raise ValueError(f"Unsupported bit depth: {bit_depth}")
        with open(file_path, 'rb') as f:
            pcm_data = f.read()
        audio_data = np.frombuffer(pcm_data, dtype=dtype)
        return audio_data
    except FileNotFoundError:
        raise FileNotFoundError(f"File not found: {file_path}")
    except Exception as e:
        raise Exception(f"Error reading PCM file: {e}")


def bandpass_filter(signal_data: np.ndarray, lowcut: float, highcut: float, 
                    sample_rate: int = 44100, order: int = 5) -> np.ndarray:
    """Apply bandpass filter to signal"""
    nyquist = 0.5 * sample_rate
    low = lowcut / nyquist
    high = highcut / nyquist
    b, a = signal.butter(order, [low, high], btype='band')
    filtered_signal = signal.filtfilt(b, a, signal_data.astype(float))
    return filtered_signal


def detect_signal_arrival_unnormalized(reference_signal: np.ndarray,
                                      received_signal: np.ndarray,
                                      threshold_ratio: float = 0.5,
                                      max_peaks_count: int = 45,
                                      sample_rate: int = 44100) -> Dict:
    """Cross-correlation arrival detection using adaptive threshold"""
    # 1. cross-correlation
    correlation = signal.correlate(received_signal, reference_signal, mode='full')
    correlation = correlation / len(reference_signal)
    lags = signal.correlation_lags(len(received_signal), len(reference_signal), mode='full')

    # 2. adaptive threshold
    adaptive_threshold = np.max(correlation) * threshold_ratio
    print(f"  Adaptive threshold: {adaptive_threshold:.4f} (max_corr: {np.max(correlation):.4f}, ratio: {threshold_ratio})")

    # 3. find peaks
    min_distance = int(0.1 * sample_rate)
    peaks, _ = find_peaks(correlation, height=adaptive_threshold, distance=min_distance)

    # 4. sort, keep the top max_peaks_count
    if len(peaks) > 0:
        # sort by correlation magnitude, descending
        sorted_indices = np.argsort(correlation[peaks])[::-1]
        peaks = peaks[sorted_indices]
        # keep the top max_peaks_count
        peaks = peaks[:max_peaks_count]

    return {
        'lags': lags,
        'peaks': peaks,
        'correlation': correlation,
        'detected': len(peaks) > 0,
        'adaptive_threshold': adaptive_threshold
    }


def plot_coarse_detection_steps(reference_signal: np.ndarray,
                                received_signal_raw: np.ndarray,
                                received_signal_filtered: np.ndarray,
                                detection_results: Dict,
                                mic_name: str,
                                threshold_ratio: float,
                                sample_rate: int = 44100):
    """Plot the three-step detection figure; the bottom panel shows detected arrivals and matched windows."""
    correlation, lags, peaks = detection_results['correlation'], detection_results['lags'], detection_results['peaks']
    adaptive_threshold = detection_results.get('adaptive_threshold', 0.0)
    time_rec = np.arange(len(received_signal_raw)) / sample_rate
    time_lags = lags / sample_rate

    fig, axes = plt.subplots(3, 1, figsize=(15, 12), sharex=False)

    # Step 1: raw vs filtered waveform
    axes[0].plot(time_rec, received_signal_raw, 'C0', alpha=0.6, lw=1, label='Raw Signal')
    axes[0].plot(time_rec, received_signal_filtered, 'C1', lw=1, label='Filtered Signal')
    axes[0].set_title('Step 1: Signal Filtering')
    axes[0].grid(True, ls=':', alpha=0.6)
    axes[0].legend(loc='upper right')

    # Step 2: cross-correlation detection (with adaptive threshold)
    axes[1].plot(time_lags, correlation, color='C2', lw=1, label='Cross-Correlation')
    axes[1].axhline(adaptive_threshold, color='gray', linestyle='--', lw=1, 
                   label=f'Adaptive Threshold={adaptive_threshold:.4f} (ratio={threshold_ratio})')
    if detection_results['detected']:
        axes[1].plot(lags[peaks] / sample_rate,
                    correlation[peaks],
                    'ro', ms=6, label='Detected Peaks')
        axes[1].plot(lags[peaks[0]] / sample_rate,
                    correlation[peaks[0]],
                    'o', color='magenta', ms=10,
                    mfc='none', mew=2, label='Best Peak')
    axes[1].set_title('Step 2: Cross-Correlation with Adaptive Threshold')
    axes[1].grid(True, ls=':', alpha=0.6)
    axes[1].legend(loc='upper right')

    # Step 3: arrivals and reference-time windows
    axes[2].plot(time_rec, received_signal_filtered,
                color='orange', lw=1, label='Filtered Signal')
    axes[2].set_title('Step 3: Detected Arrival and Matched Region')

    if detection_results['detected']:
        ref_duration = len(reference_signal) / sample_rate
        y_min, y_max = axes[2].get_ylim()

        # draw each detected peak
        for i, peak_idx in enumerate(peaks):
            arrival_samples = lags[peak_idx]
            if 0 <= arrival_samples < len(received_signal_filtered):
                arrival_time = arrival_samples / sample_rate

                # vertical dashed line (signal arrival time)
                axes[2].axvline(arrival_time, color='red', lw=2,
                                ls='--', alpha=0.9)

                # matched window (pink background)
                rect_start = arrival_time
                rect_end = arrival_time + ref_duration
                axes[2].axvspan(rect_start, rect_end,
                                color='pink', alpha=0.3)

        legend_elements = [
            Line2D([0], [0], color='orange', lw=2, label='Filtered Signal'),
            Line2D([0], [0], color='red', lw=2, ls='--', label='Detected Arrival'),
            Patch(facecolor='pink', edgecolor='none', alpha=0.3, label='Matched Region')
        ]
        axes[2].legend(handles=legend_elements, loc='upper right')

    axes[2].set_xlabel('Time (s)')
    axes[2].set_ylabel('Amplitude')
    axes[2].grid(True, ls=':', alpha=0.6)
    axes[2].set_xlim(time_rec[0], time_rec[-1])

    # suptitle: mic name
    fig.suptitle(f'{mic_name} - Signal Arrival Detection', fontsize=16)

    plt.tight_layout(rect=[0, 0, 1, 0.97])
    plt.show()


def get_db_top(pcm_file_path: str,
               reference_wav_file: str = "./model2.wav",
               threshold_ratio: float = 0.3,
               max_num_peaks: int = 45,
               sample_rate: int = 44100,
               lowcut: float = 2600,
               highcut: float = 2800,
               debug: bool = False) -> float:
    """
    Process one top-mic PCM file and return its db_top value.
    
    Args:
        pcm_file_path: path to the top-mic PCM file
        reference_wav_file: reference-signal WAV file (default: "./model2.wav")
        threshold_ratio: peak threshold ratio (default: 0.3)
        max_num_peaks: maximum number of peaks (default: 45)
        sample_rate: sample rate (default: 44100)
        lowcut: band-pass low cutoff frequency (default: 2600 Hz)
        highcut: band-pass high cutoff frequency (default: 2800 Hz)
        debug: enable debug mode (plotting); default: False
    
    Returns:
        db_top: the top-mic dB value
    """
    # PCM full-scale value for dB (signed 16-bit max)
    MAX_VAL_PCM = float(2**(15))
    
    # 1. read and pre-process the reference signal
    reference_signal, _ = read_wav(reference_wav_file)
    ref_demeaned = reference_signal.astype(float) - np.mean(reference_signal)
    ref_filtered = bandpass_filter(ref_demeaned, lowcut, highcut, sample_rate)
    
    # 2. read and pre-process the top-mic signal
    pcm_top_raw = read_pcm(pcm_file_path)
    pcm_top_demeaned = pcm_top_raw.astype(float) - np.mean(pcm_top_raw)
    pcm_top_filtered_raw = bandpass_filter(pcm_top_demeaned, lowcut, highcut, sample_rate)
    
    # 3. detect signal arrivals
    results_top = detect_signal_arrival_unnormalized(
        ref_filtered, pcm_top_filtered_raw, threshold_ratio, max_num_peaks, sample_rate
    )
    
    # debug mode: plot the detection steps
    if debug and results_top['detected']:
        mic_name = f"Top Microphone - {pcm_file_path}"
        plot_coarse_detection_steps(
            ref_filtered, pcm_top_demeaned, pcm_top_filtered_raw,
            results_top, mic_name, threshold_ratio, sample_rate
        )
    
    # 4. RMS over all matched regions (top mic)
    peaks_top = results_top['peaks']
    lags_top = results_top['lags']
    ref_len = len(ref_filtered)
    
    rms_normalized_top_list = []
    for i, peak_idx in enumerate(peaks_top):
        arrival_idx = lags_top[peak_idx]
        start_idx = max(0, arrival_idx)
        end_idx = min(len(pcm_top_filtered_raw), start_idx + ref_len)
        
        if end_idx > start_idx:
            window_signal = pcm_top_filtered_raw[start_idx:end_idx]
            window_signal_normalized = window_signal / MAX_VAL_PCM
            rms_normalized_val = np.sqrt(np.mean(window_signal_normalized ** 2))
            rms_normalized_top_list.append(rms_normalized_val)
    
    # 5. normalised RMS and dB value
    rms_normalized_top = np.sqrt(np.mean(np.array(rms_normalized_top_list) ** 2)) if len(rms_normalized_top_list) > 0 else 0.0
    db_top = 20 * np.log10(rms_normalized_top) if rms_normalized_top > 0 else float('-inf')
    
    return db_top


if __name__ == "__main__":
    # example usage
    # pcm_file = "acoustic_dis/80m/audio_right_CDEF86FDE4B5_angle200_rssi-84_20260102_021257.pcm"  # replace with an actual PCM file path
    pcm_file = "acoustic_dis/70m/audio_right_CDEF86FDE4B5_angle125_rssi-82_20260102_013315.pcm"
    
    # normal mode (no plotting)
    db_value = get_db_top(pcm_file, threshold_ratio=0.1,debug=True)
    # add the 100 dB calibration offset (dBSL -> real dB)
    db_value += 100
    print(f"db_top: {db_value:.4f} dB")

    
    # Debug模式（绘图）
    # db_value = get_db_top(pcm_file, debug=True)
    # print(f"db_top: {db_value:.4f} dB")
