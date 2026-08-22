package com.kwikquant.strategy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * {@link WorkerEnvironmentProvisioner} 自动搭建：建 venv + pip install 的命令编排、
 * 目标目录安全护栏（系统目录/非虚拟环境拒绝清除、不向非 venv 解释器装依赖）、
 * 失败分支（版本过低/建 venv 败/装依赖败/超时/路径不可反推）与 pip 环境变量注入。
 */
class WorkerEnvironmentProvisionerTest {

    private SubprocessExecutor executor;
    private WorkerEnvironmentProvisioner provisioner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        executor = mock(SubprocessExecutor.class);
        provisioner = new WorkerEnvironmentProvisioner(executor);
    }

    private static SubprocessResult ok() {
        return SubprocessResult.of(0, "", "", false);
    }

    /** tempDir 下造一个"真虚拟环境"形态的目录（含 pyvenv.cfg），返回解释器路径。 */
    private Path fakeVenvPython(String venvName) throws IOException {
        Path venv = tempDir.resolve(venvName);
        Files.createDirectories(venv.resolve("bin"));
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\n");
        return venv.resolve("bin").resolve("python");
    }

    @Test
    void provisionable_pathYes_bareNameNo() {
        assertThat(WorkerEnvironmentProvisioner.provisionable(".venv/bin/python"))
                .isTrue();
        assertThat(WorkerEnvironmentProvisioner.provisionable("/opt/kq/venv/bin/python3.12"))
                .isTrue();
        assertThat(WorkerEnvironmentProvisioner.provisionable("python3")).isFalse();
        assertThat(WorkerEnvironmentProvisioner.provisionable(null)).isFalse();
    }

    @Test
    void deriveVenvDir_walksUpTwoLevels() {
        assertThat(WorkerEnvironmentProvisioner.deriveVenvDir(".venv/bin/python"))
                .isEqualTo(".venv");
        assertThat(WorkerEnvironmentProvisioner.deriveVenvDir("/opt/kq/venv/bin/python3.12"))
                .isEqualTo("/opt/kq/venv");
        assertThat(WorkerEnvironmentProvisioner.deriveVenvDir("bin/python")).isNull();
        assertThat(WorkerEnvironmentProvisioner.deriveVenvDir("python")).isNull();
    }

    @Test
    void provision_missingVenv_checksVersionThenCreatesThenInstalls() throws IOException {
        // 目标目录不存在（fresh clone）：python3 版本校验 → venv --clear → pip install
        Path python = tempDir.resolve("fresh").resolve("bin").resolve("python");
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(ok());

        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision(python.toString(), true);
        assertThat(result.success()).isTrue();
        assertThat(result.detail()).contains("已创建虚拟环境");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envs = ArgumentCaptor.forClass(Map.class);
        verify(executor, times(3)).run(commands.capture(), envs.capture(), isNull(), anyLong());
        assertThat(commands.getAllValues().get(0))
                .containsExactly("python3", "-c", "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)");
        assertThat(commands.getAllValues().get(1))
                .containsExactly(
                        "python3",
                        "-m",
                        "venv",
                        "--clear",
                        tempDir.resolve("fresh").toString());
        assertThat(commands.getAllValues().get(2))
                .containsExactly(python.toString(), "-m", "pip", "install", "-r", "requirements-worker.txt");
        // pip 需要 HOME（缓存/配置）；PATH/PYTHONPATH 由 RealSubprocessExecutor 统一注入
        assertThat(envs.getAllValues().get(2)).containsKey("HOME");
    }

    @Test
    void provision_depsOnly_intoExistingVenv_skipsVenvCreation() throws IOException {
        Path python = fakeVenvPython("broken-deps");
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(ok());

        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision(python.toString(), false);
        assertThat(result.success()).isTrue();
        verify(executor, times(1)).run(anyList(), any(), any(), anyLong());
    }

    @Test
    void provision_existingDirNotVenv_refusesClearAndInstall() throws IOException {
        // 目标目录存在但无 pyvenv.cfg：既不能 --clear（防误伤），也不往里装依赖；
        // conda 等非 venv 环境给出手工安装出路
        Path dir = tempDir.resolve("not-a-venv");
        Files.createDirectories(dir.resolve("bin"));
        Path python = dir.resolve("bin").resolve("python");

        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision(python.toString(), true);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("不是 Python 虚拟环境");
        assertThat(result.detail()).contains("pip install -r requirements-worker.txt");
        verifyNoInteractions(executor);
    }

    @Test
    void provision_systemInterpreter_depsOnly_refusesPipInstall() {
        // /usr/bin/python3 → 反推 /usr：存在但非虚拟环境，不向系统 python pip install
        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision("/usr/bin/python3", false);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("不是 Python 虚拟环境");
        verifyNoInteractions(executor);
    }

    @Test
    void provision_newDirUnderSystemRoot_refused() {
        // 误配解释器指向系统目录下的不存在路径时，不允许在那里新建虚拟环境（深层路径同样拦）
        WorkerEnvironmentProvisioner.ProvisionResult result =
                provisioner.provision("/usr/kq-autosetup/bin/python", true);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("系统目录");
        verifyNoInteractions(executor);

        WorkerEnvironmentProvisioner.ProvisionResult deep = provisioner.provision("/usr/local/kqvenv/bin/python", true);
        assertThat(deep.success()).isFalse();
        assertThat(deep.detail()).contains("系统目录");
    }

    @Test
    void provision_pythonVersionTooLow_reportsAndStops() throws IOException {
        Path python = tempDir.resolve("fresh").resolve("bin").resolve("python");
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(1, "", "", false));

        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision(python.toString(), true);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("版本过低");
        // 只跑了版本校验，没有建 venv / pip install
        verify(executor, times(1)).run(anyList(), any(), any(), anyLong());
    }

    @Test
    void provision_venvCreationFails_reportsInstallHint_noPipAttempt() throws IOException {
        Path python = tempDir.resolve("fresh").resolve("bin").resolve("python");
        when(executor.run(anyList(), any(), any(), anyLong()))
                .thenReturn(ok())
                .thenReturn(SubprocessResult.of(
                        1, "", "Error: Command '['python3', '-m', 'venv']' returned non-zero exit status 1.", false));
        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision(python.toString(), true);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("创建虚拟环境失败");
        assertThat(result.detail()).contains("python3-venv");
        verify(executor, times(2)).run(anyList(), any(), any(), anyLong());
    }

    @Test
    void provision_pipFails_reportsMirrorHint() throws IOException {
        Path python = fakeVenvPython("pip-fails");
        when(executor.run(anyList(), any(), any(), anyLong()))
                .thenReturn(SubprocessResult.of(
                        1, "", "Could not find a version that satisfies the requirement httpx", false));
        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision(python.toString(), false);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("依赖安装失败");
        assertThat(result.detail()).contains("PIP_INDEX_URL");
    }

    @Test
    void provision_pipTimeout_reportsTimeout() throws IOException {
        Path python = fakeVenvPython("pip-timeout");
        when(executor.run(anyList(), any(), any(), anyLong())).thenReturn(SubprocessResult.of(-1, "", "", true));
        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision(python.toString(), false);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("超时");
    }

    @Test
    void provision_venvDirNotDerivable_failsFast() {
        WorkerEnvironmentProvisioner.ProvisionResult result = provisioner.provision("bin/python", true);
        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("反推");
        verifyNoInteractions(executor);
    }
}
