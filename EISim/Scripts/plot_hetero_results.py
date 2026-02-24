import sys
import os
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib
from matplotlib.ticker import FuncFormatter
from scipy.stats import t
from os import listdir, makedirs
from os.path import join, exists, isdir

# Use non-interactive backend
matplotlib.use('Agg')

def parse_results(output_dir):
    """
    Parses evaluation results from the output directory.
    Assumes structure: output_dir/episode_X/results.csv
    """
    results = {}
    
    if not exists(output_dir):
        print(f"Error: Directory {output_dir} does not exist.")
        return None

    episode_folders = [f for f in listdir(output_dir) if isdir(join(output_dir, f))]
    if not episode_folders:
        print(f"No episode folders found in {output_dir}")
        return None
        
    for episode in episode_folders:
        episode_path = join(output_dir, episode)
        csv_files = [f for f in listdir(episode_path) if f.endswith('.csv')]
        
        for csv_file in csv_files:
            csv_path = join(episode_path, csv_file)
            try:
                df = pd.read_csv(csv_path)
                
                # Iterate through rows (scenarios)
                for index, row in df.iterrows():
                    ctrl_algo = row.get('Orchestration algorithm', 'Unknown')
                    dev_count = row.get('Edge devices count', 0)
                    
                    if ctrl_algo not in results:
                        results[ctrl_algo] = {}
                    
                    if dev_count not in results[ctrl_algo]:
                        results[ctrl_algo][dev_count] = {
                            'avgExecDelay': [],
                            'avgEnergyEdge': [],
                            'avgEnergyMist': [],
                            'tasksFailedDelay': [],
                            'edgeSuccessRate': [],
                            'mistSuccessRate': []
                        }
                    
                    # Append metrics
                    metrics = results[ctrl_algo][dev_count]
                    metrics['avgExecDelay'].append(row.get('Average execution delay (s)', 0))
                    metrics['avgEnergyEdge'].append(row.get('Average Edge energy consumption (Wh/Data center)', 0))
                    metrics['avgEnergyMist'].append(row.get('Average Mist energy consumption (Wh/Device)', 0))
                    
                    n_tasks = row.get('Number of generated tasks', 1) - row.get('Tasks not generated due to the death of devices', 0)
                    if n_tasks > 0:
                        metrics['tasksFailedDelay'].append(100 * row.get('Tasks failed (delay)', 0) / n_tasks)
                    else:
                         metrics['tasksFailedDelay'].append(0)

            except Exception as e:
                print(f"Error reading {csv_path}: {e}")

    return results

def plot_bar_chart(data, metric_name, y_label, title, filename, output_path):
    """
    Plots a grouped bar chart for a specific metric.
    """
    if not data:
        return

    # Extract control algorithms and device counts
    ctrl_algos = list(data.keys())
    if not ctrl_algos:
        return
        
    device_counts = sorted(list(data[ctrl_algos[0]].keys()))
    
    x = np.arange(len(device_counts))
    width = 0.25
    multiplier = 0
    
    fig, ax = plt.subplots(figsize=(10, 6))

    for algo in ctrl_algos:
        means = []
        cis = []
        
        for count in device_counts:
            values = data[algo].get(count, {}).get(metric_name, [])
            if values:
                mean = np.mean(values)
                std = np.std(values)
                n = len(values)
                # 95% confidence interval
                if n > 1:
                    ci = t.interval(0.95, n-1, loc=mean, scale=std/np.sqrt(n))[1] - mean
                else:
                    ci = 0
                means.append(mean)
                cis.append(ci)
            else:
                means.append(0)
                cis.append(0)
        
        offset = width * multiplier
        rects = ax.bar(x + offset, means, width, label=algo, yerr=cis, capsize=5)
        multiplier += 1

    ax.set_ylabel(y_label)
    ax.set_xlabel('Edge Devices Count')
    ax.set_title(title)
    ax.set_xticks(x + width * (len(ctrl_algos) - 1) / 2)
    ax.set_xticklabels(device_counts)
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.7)

    plt.tight_layout()
    if output_path:
        plt.savefig(join(output_path, filename))
        print(f"Saved plot: {join(output_path, filename)}")
    else:
        plt.show()
    plt.close()

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python plot_hetero_results.py <evaluation_output_dir> [save_dir]")
        sys.exit(1)
        
    output_dir = sys.argv[1]
    save_dir = sys.argv[2] if len(sys.argv) > 2 else "."
    
    if not exists(save_dir):
        makedirs(save_dir)
        
    print(f"Parsing results from {output_dir}...")
    results = parse_results(output_dir)
    
    if results:
        print("Plotting results...")
        plot_bar_chart(results, 'avgExecDelay', 'Seconds', 'Average Execution Delay', 'avg_exec_delay.pdf', save_dir)
        plot_bar_chart(results, 'avgEnergyEdge', 'Wh', 'Avg Edge Server Energy', 'avg_energy_edge.pdf', save_dir)
        plot_bar_chart(results, 'avgEnergyMist', 'Wh', 'Avg Device Energy', 'avg_energy_device.pdf', save_dir)
        plot_bar_chart(results, 'tasksFailedDelay', '%', 'Tasks Failed (Delay)', 'tasks_failed_delay.pdf', save_dir)
        print("Done.")
    else:
        print("No results found to plot.")
