import json
import os

nb_path = '/home/vdo25/Desktop/ubicomp/eisim-revision-cpu/Environment_setup/revision/Generate_xml_files_hetero.ipynb'

print(f"Loading notebook from {nb_path}...")
with open(nb_path, 'r') as f:
    nb = json.load(f)

print("Updating paths from 'tmp' to 'revision'...")
count = 0
for cell in nb['cells']:
    if cell['cell_type'] == 'code':
        source = cell['source']
        new_source = []
        modified = False
        for line in source:
            if 'EISim_settings/tmp/' in line:
                new_line = line.replace('EISim_settings/tmp/', 'EISim_settings/revision/')
                new_source.append(new_line)
                modified = True
                count += 1
            else:
                new_source.append(line)
        
        if modified:
            cell['source'] = new_source

print(f"Updated {count} paths.")

# 4. Save
print("Saving notebook...")
with open(nb_path, 'w') as f:
    json.dump(nb, f, indent=1)
print("Done.")
