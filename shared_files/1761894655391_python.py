import subprocess

input_file = "ElevenLabs_2025-10-14T10_40_49_Liam_pre_sp100_s50_sb75_v3.mp3"
output_file = "palindrome_number_fast.mp3"
speed = 1.5

subprocess.run([
    "ffmpeg", "-y", "-i", input_file,
    "-filter:a", f"atempo={speed}",
    output_file
])
print("File MP3 đã tăng tốc:", output_file)
