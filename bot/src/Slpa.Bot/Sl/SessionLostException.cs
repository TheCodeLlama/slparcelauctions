namespace Slpa.Bot.Sl;

public sealed class SessionLostException : Exception
{
    public SessionLostException(string message) : base(message) { }
}
