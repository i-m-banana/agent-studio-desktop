export function releaseImageRequest(releaseId: string, manifestSha256: string) {
  if (!/^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$/.test(releaseId) || !/^[0-9a-f]{64}$/.test(manifestSha256)) {
    throw new Error('请填写成功候选结果中的候选 ID 和完整清单 SHA-256')
  }
  return `请只使用 build_release_candidate_image 工具，参数严格为 ${JSON.stringify({ releaseId, manifestSha256 })}，构建这个候选的独立应用镜像。不得提供路径、镜像标签、命令、参数、环境变量或生产切换选项。`
}
