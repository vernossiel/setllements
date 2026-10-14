source .venv/bin/activate

python fetch_contributors.py

cd skins
python clothing_generator.py
python face_generator.py