import AVFoundation
import AppKit

let args = CommandLine.arguments
guard args.count >= 2 else {
    fputs("usage: GrabVideoFrames <video.mov> [outdir]\n", stderr)
    exit(1)
}
let path = args[1]
let outDir = args.count >= 3 ? args[2] : "/tmp/sarif_vid_frames"
let url = URL(fileURLWithPath: path)
let asset = AVURLAsset(url: url)
let gen = AVAssetImageGenerator(asset: asset)
gen.appliesPreferredTrackTransform = true
gen.requestedTimeToleranceAfter = .zero
gen.requestedTimeToleranceBefore = .zero

try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let duration = CMTimeGetSeconds(asset.duration)
let seconds: [Double] = [0.2, 1.0, 2.0, 3.5, 5.0, 7.0, 9.0, 12.0, 15.0, 20.0, 25.0, 30.0]
    .filter { $0 < max(duration - 0.1, 0.5) }

for (i, sec) in seconds.enumerated() {
    let t = CMTime(seconds: sec, preferredTimescale: 600)
    var actual = CMTime.zero
    do {
        let cg = try gen.copyCGImage(at: t, actualTime: &actual)
        let rep = NSBitmapImageRep(cgImage: cg)
        guard let data = rep.representation(using: .png, properties: [:]) else { continue }
        let out = URL(fileURLWithPath: outDir).appendingPathComponent(String(format: "frame_%02d_%.1fs.png", i, sec))
        try data.write(to: out)
        print(out.path)
    } catch {
        fputs("frame \(sec)s: \(error)\n", stderr)
    }
}
print("duration_sec=\(duration)")
