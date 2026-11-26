using StarHub.Core;
using Xunit;

namespace StarHub.Tests;

public sealed class WorkbookLearningTests
{
    [Theory]
    [InlineData("应该是七百二十元。", "720")]
    [InlineData("负二点五", "-2.5")]
    [InlineData("是10.20元", "10.2")]
    [InlineData("二十万元", "200000")]
    [InlineData("为什么乘以120？", null)]
    [InlineData("120 还是 150", null)]
    public void SpokenAnswerDoesNotMistakeQuestionsForAmounts(string text, string? expected) =>
        Assert.Equal(expected is null ? (decimal?)null : decimal.Parse(expected, System.Globalization.CultureInfo.InvariantCulture), WorkbookLesson.SpokenNumber(text));

    [Theory]
    [InlineData("答案十二百七十元。", true)]
    [InlineData("答案是二百七十元。", false)]
    [InlineData("答案为什么是二百七十元？", false)]
    public void AmbiguousDictationKeepsTheAnswerEditable(string text, bool unclear) =>
        Assert.Equal(unclear, WorkbookLesson.UnclearAmount(text));

    [Fact]
    public void NewBatchChangesEvidenceAndCanResumeInItsOwnProject()
    {
        string root = Directory.CreateDirectory(Path.Combine(AppContext.BaseDirectory, "starhub-lesson-" + Guid.NewGuid().ToString("N"))).FullName;
        try
        {
            File.WriteAllText(Path.Combine(root, "materials.csv"), "Product,Quantity,Unit price\nM,30,6\n");
            File.WriteAllText(Path.Combine(root, "work.csv"), "Product,Hours,Units,Wages\nM,25,60,45\nN,75,100,15\n");
            File.WriteAllText(Path.Combine(root, "expenses.csv"), "Overhead\n300\n100\n");
            var plan = LocalWork.Plan(WorkKind.CostWorkbook, root, root, root);
            var file = LocalWork.Execute(new(Guid.NewGuid().ToString("N"), "work", plan, null), default);
            var lesson = WorkbookLesson.Read(file.Path, true);
            Assert.Equal(new decimal[] { 180, 100, 5.42m, 1 }, lesson.Steps.Select(s => s.Answer));
            Assert.Contains(lesson.Steps[0].Cells, c => c.Value.Contains("materials.csv#2"));
            Assert.Contains(lesson.Steps[3].Cells, c => c.Value == "N");
            Assert.Equal(198, lesson.Review.Answer);
            Assert.Contains("2   M   ·   30", lesson.Steps[0].Cells[0].SourceRows![1]);
            Assert.NotEqual(WorkbookLesson.Read(file.Path, false, new DateOnly(2026, 11, 25)).Review.Answer,
                WorkbookLesson.Read(file.Path, false, new DateOnly(2026, 11, 26)).Review.Answer);
            Assert.Equal(lesson.Revision, WorkbookLesson.Read(file.Path, false).Revision);
            var store = new LocalStore(root);
            var project = store.CreateProject("工厂");
            store.StartTurn("work", "成本表", "local", null, project.Id);
            var day = new DateOnly(2026, 11, 25);
            var state = LearningState.Begin(day).Record("领料 180 元") with { Next = 1, Draft = "100", Personal = "找仓库核对" };
            store.SaveLearning("work", file.Path, lesson.Revision, state);
            var reopened = new LocalStore(root);
            Assert.Null(reopened.RecentLesson(null, null));
            Assert.Equal(("work", file.Path), reopened.RecentLesson(null, project.Id));
            var resumed = reopened.ReadLearning("work", file.Path, lesson.Revision)!.OpenOn(day.AddDays(1));
            Assert.True(resumed.Reviewing);
            Assert.Equal(state.Draft, resumed.Draft);
            Assert.Equal(state.Notes, resumed.Notes);
            Assert.Equal(state.Personal, resumed.Personal);
            Assert.False((resumed with { Reviewing = false }).OpenOn(day.AddDays(1)).Reviewing);
            Assert.Null(reopened.ReadLearning("work", file.Path, "changed"));
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            Directory.Delete(root, true);
        }
    }
}
