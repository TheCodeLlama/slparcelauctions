namespace Slpa.Bot.Options;

public sealed class BackendOptions
{
    public const string SectionName = "Backend";

    public string BaseUrl { get; set; } = "http://localhost:8080";
    public string SharedSecret { get; set; } = "";
}
